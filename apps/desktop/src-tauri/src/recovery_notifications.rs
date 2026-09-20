//! Owned, body-free recovery notifications. The legacy plugin does not expose
//! cancellation commands on desktop, so recovery uses explicit OS identifiers.
use serde::Deserialize;
use serde_json::Value;
#[cfg(any(test, target_os = "macos", target_os = "windows"))]
use sha2::{Digest, Sha256};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, State};
#[cfg(any(target_os = "macos", target_os = "windows"))]
use tauri::{Emitter, Manager};

#[derive(Default, Clone)]
pub struct RecoveryNotificationState(Arc<Mutex<String>>);
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Request {
    owner: String,
    kind: String,
    id: Option<String>,
    target: Option<Value>,
}
#[cfg(any(test, target_os = "macos", target_os = "windows"))]
fn identifier(id: &str) -> String {
    format!("{:x}", Sha256::digest(id.as_bytes()))
}
fn validate(input: &Request) -> Result<(), String> {
    if input.owner.len() > 2048
        || !["OWNER", "SHOW", "CANCEL", "CANCEL_ALL"].contains(&input.kind.as_str())
    {
        return Err("INVALID_NOTIFICATION".into());
    }
    if ["SHOW", "CANCEL"].contains(&input.kind.as_str())
        && !input
            .id
            .as_ref()
            .is_some_and(|id| !id.is_empty() && id.len() <= 4096)
    {
        return Err("INVALID_NOTIFICATION_ID".into());
    }
    if input.kind == "SHOW" {
        let target = input.target.as_ref().ok_or("INVALID_NOTIFICATION_ROUTE")?;
        let fields = target.as_object().ok_or("INVALID_NOTIFICATION_ROUTE")?;
        if fields.keys().any(|key| {
            ![
                "kind",
                "value",
                "nodeOrigin",
                "messageId",
                "notification",
                "notificationOwner",
            ]
            .contains(&key.as_str())
        }) || target["notification"] != true
            || target["notificationOwner"].as_str() != Some(input.owner.as_str())
        {
            return Err("INVALID_NOTIFICATION_ROUTE".into());
        }

        if target["kind"] != "conversation"
            || !target["value"]
                .as_str()
                .is_some_and(|s| !s.is_empty() && s.len() <= 200)
            || !target["messageId"]
                .as_str()
                .is_some_and(|s| !s.is_empty() && s.len() <= 128)
        {
            return Err("INVALID_NOTIFICATION_ROUTE".into());
        }
    }
    Ok(())
}
#[cfg(any(target_os = "macos", target_os = "windows"))]
fn route(app: &AppHandle, owner: &str, target: Value) {
    let Some(state) = app.try_state::<RecoveryNotificationState>() else {
        return;
    };
    if state
        .0
        .lock()
        .map(|current| current.as_str() != owner)
        .unwrap_or(true)
    {
        return;
    }
    let _ = app.emit("desktop://deep-link", target);
    crate::lifecycle::show_main_window(app);
}
#[tauri::command]
pub async fn recovery_notification(
    app: AppHandle,
    state: State<'_, RecoveryNotificationState>,
    input: Request,
) -> Result<(), String> {
    validate(&input)?;
    let shared = state.inner().clone();
    if input.kind == "OWNER" {
        *shared.0.lock().map_err(|_| "NOTIFICATION_OWNER_LOCK")? = input.owner.clone();
    }
    let (sender, receiver) = tokio::sync::oneshot::channel();
    let handle = app.clone();
    app.run_on_main_thread(move || {
        let result = (|| {
            let current = shared.0.lock().map_err(|_| "NOTIFICATION_OWNER_LOCK")?;
            if *current != input.owner {
                return Err("STALE_NOTIFICATION_OWNER".into());
            }
            platform(&handle, &input)
        })();
        let _ = sender.send(result);
    })
    .map_err(|_| "NOTIFICATION_DISPATCH_FAILED")?;
    receiver
        .await
        .map_err(|_| "NOTIFICATION_DISPATCH_CANCELLED")?
}

#[cfg(target_os = "macos")]
fn platform(app: &AppHandle, input: &Request) -> Result<(), String> {
    use objc2::{
        msg_send,
        rc::Retained,
        runtime::{AnyClass, AnyObject},
    };
    use objc2_foundation::{NSArray, NSDictionary, NSString};
    install_mac_delegate(app)?;
    // NSUserNotificationCenter is also the backend used by the installed legacy
    // Tauri plugin. Staying on that center lets OWNER clear its old notifications.
    let class = AnyClass::get(c"NSUserNotificationCenter").ok_or("NOTIFICATION_UNSUPPORTED")?;
    // SAFETY: All calls execute on Tauri's main thread; selectors and signatures
    // are the documented NSObject/NSUserNotificationCenter APIs. Retained values
    // keep returned Foundation objects alive for the duration of each operation.
    unsafe {
        let center: Retained<AnyObject> = msg_send![class, defaultUserNotificationCenter];
        if input.kind == "OWNER" || input.kind == "CANCEL_ALL" {
            let _: () = msg_send![&*center, removeAllDeliveredNotifications];
            return Ok(());
        }
        let id = NSString::from_str(&identifier(input.id.as_deref().unwrap_or_default()));
        let delivered: Retained<NSArray<AnyObject>> = msg_send![&*center, deliveredNotifications];
        for item in delivered.iter() {
            let existing: Option<Retained<NSString>> = msg_send![&*item, identifier];
            if existing.as_deref() == Some(&*id) {
                let _: () = msg_send![&*center, removeDeliveredNotification: &*item];
            }
        }
        if input.kind == "CANCEL" {
            return Ok(());
        }
        let notification_class =
            AnyClass::get(c"NSUserNotification").ok_or("NOTIFICATION_UNSUPPORTED")?;
        let notification: Retained<AnyObject> = msg_send![notification_class, new];
        let title = NSString::from_str("MeshX");
        let body = NSString::from_str("你有新消息，打开 MeshX 查看");
        let encoded =
            serde_json::to_string(&serde_json::json!({"owner":input.owner,"target":input.target}))
                .map_err(|_| "INVALID_NOTIFICATION_ROUTE")?;
        let key = NSString::from_str("meshxRecoveryRoute");
        let value = NSString::from_str(&encoded);
        let info = NSDictionary::from_slices(&[&*key], &[&*value]);
        let _: () = msg_send![&*notification, setIdentifier: &*id];
        let _: () = msg_send![&*notification, setTitle: &*title];
        let _: () = msg_send![&*notification, setInformativeText: &*body];
        let _: () = msg_send![&*notification, setUserInfo: &*info];
        let _: () = msg_send![&*center, deliverNotification: &*notification];
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn install_mac_delegate(app: &AppHandle) -> Result<(), String> {
    use objc2::{
        msg_send,
        rc::Retained,
        runtime::{AnyClass, AnyObject, ClassBuilder, Sel},
        sel, ClassType,
    };
    use objc2_foundation::{NSDictionary, NSObject, NSString};
    use std::sync::OnceLock;
    static APP: OnceLock<AppHandle> = OnceLock::new();
    static DELEGATE: OnceLock<&'static AnyClass> = OnceLock::new();
    let _ = APP.set(app.clone());
    extern "C" fn activated(_: &AnyObject, _: Sel, _: &AnyObject, notification: &AnyObject) {
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            // SAFETY: NSUserNotificationCenter invokes this registered selector
            // with an NSUserNotification. userInfo was written as string metadata.
            unsafe {
                let info: Option<Retained<NSDictionary<NSString, NSString>>> =
                    msg_send![notification, userInfo];
                let Some(info) = info else { return };
                let Some(raw) = info.objectForKey(&NSString::from_str("meshxRecoveryRoute")) else {
                    return;
                };
                let Ok(value) = serde_json::from_str::<Value>(&raw.to_string()) else {
                    return;
                };
                if let (Some(app), Some(owner), Some(target)) =
                    (APP.get(), value["owner"].as_str(), value.get("target"))
                {
                    route(app, owner, target.clone());
                }
            }
        }));
    }
    let delegate_class = DELEGATE.get_or_init(|| {
        let mut builder =
            ClassBuilder::new(c"MeshXRecoveryNotificationDelegate", NSObject::class())
                .expect("unique notification delegate class");
        // SAFETY: The selector takes two Objective-C objects and returns void,
        // exactly matching userNotificationCenter:didActivateNotification:.
        unsafe {
            builder.add_method(
                sel!(userNotificationCenter:didActivateNotification:),
                activated as extern "C" fn(_, _, _, _),
            );
        }
        builder.register()
    });
    thread_local! { static INSTANCE: std::cell::OnceCell<Retained<AnyObject>> = const { std::cell::OnceCell::new() }; }
    let center_class =
        AnyClass::get(c"NSUserNotificationCenter").ok_or("NOTIFICATION_UNSUPPORTED")?;
    INSTANCE.with(|slot| unsafe {
        let delegate = slot.get_or_init(|| msg_send![*delegate_class, new]);
        let center: Retained<AnyObject> = msg_send![center_class, defaultUserNotificationCenter];
        let _: () = msg_send![&*center, setDelegate: &**delegate];
    });
    Ok(())
}

#[cfg(target_os = "windows")]
fn platform(app: &AppHandle, input: &Request) -> Result<(), String> {
    use windows::{
        core::HSTRING,
        Data::Xml::Dom::XmlDocument,
        Foundation::TypedEventHandler,
        UI::Notifications::{NotificationSetting, ToastNotification, ToastNotificationManager},
    };
    let apply = || -> windows::core::Result<()> {
        let app_id = HSTRING::from(&app.config().identifier);
        let history = ToastNotificationManager::History()?;
        if input.kind == "OWNER" || input.kind == "CANCEL_ALL" {
            return history.ClearWithId(&app_id);
        }
        let id = identifier(input.id.as_deref().unwrap_or_default());
        let tag = HSTRING::from(&id[..16]);
        let group = HSTRING::from(&id[16..32]);
        history.RemoveGroupedTagWithId(&tag, &group, &app_id)?;
        if input.kind == "CANCEL" {
            return Ok(());
        }
        let xml = XmlDocument::new()?;
        xml.LoadXml(&HSTRING::from("<toast><visual><binding template=\"ToastGeneric\"><text>MeshX</text><text>你有新消息，打开 MeshX 查看</text></binding></visual></toast>"))?;
        let toast = ToastNotification::CreateToastNotification(&xml)?;
        toast.SetTag(&tag)?;
        toast.SetGroup(&group)?;
        let handle = app.clone();
        let owner = input.owner.clone();
        let target = input.target.clone().unwrap_or(Value::Null);
        toast.Activated(&TypedEventHandler::<
            ToastNotification,
            windows::core::IInspectable,
        >::new(move |_, _| {
            route(&handle, &owner, target.clone());
            Ok(())
        }))?;
        let notifier = ToastNotificationManager::CreateToastNotifierWithId(&app_id)?;
        if notifier.Setting()? != NotificationSetting::Enabled {
            return Err(windows::core::Error::from_hresult(windows::core::HRESULT(
                0x80070005u32 as i32,
            )));
        }
        notifier.Show(&toast)
    };
    apply().map_err(|_| "NOTIFICATION_OS_REQUEST_FAILED".into())
}
#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn platform(_: &AppHandle, _: &Request) -> Result<(), String> {
    Err("NOTIFICATION_CANCELLATION_UNSUPPORTED".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn scoped_identifier_and_route_validation() {
        assert_ne!(
            identifier("[\"node|1\",\"m\"]"),
            identifier("[\"node|2\",\"m\"]")
        );
        assert_eq!(identifier("same"), identifier("same"));
        let mut request = Request {
            owner: "node|1".into(),
            kind: "SHOW".into(),
            id: Some("m".into()),
            target: None,
        };
        assert!(validate(&request).is_err());
        request.target = Some(
            serde_json::json!({"kind":"conversation","value":"group:21","messageId":"m","notification":true,"notificationOwner":"node|1"}),
        );
        assert!(validate(&request).is_ok());
        request.kind = "RUN".into();
        assert!(validate(&request).is_err());
    }
}
