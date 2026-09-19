package com.lanchat.control.device;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ControlDeviceMapper {

    @Select("""
            SELECT id FROM organization
            WHERE organization_key = #{organizationKey} AND status = 'ACTIVE'
            LIMIT 1
            """)
    Long selectOrganizationId(@Param("organizationKey") String organizationKey);

    @Select("""
            SELECT policy.*
            FROM organization_policy policy
            JOIN organization ON organization.id = policy.organization_id
            WHERE organization.organization_key = #{organizationKey}
            LIMIT 1
            """)
    DevicePolicyRecord selectPolicy(@Param("organizationKey") String organizationKey);

    @Select("""
            SELECT policy.*
            FROM organization_policy policy
            JOIN organization ON organization.id = policy.organization_id
            WHERE organization.organization_key = #{organizationKey}
            FOR UPDATE
            """)
    DevicePolicyRecord lockPolicy(@Param("organizationKey") String organizationKey);

    @Update("""
            UPDATE organization_policy policy
            JOIN organization ON organization.id = policy.organization_id
            SET policy.device_approval_mode = #{approvalMode},
                policy.credential_validity_days = #{credentialValidityDays},
                policy.max_offline_hours = #{maxOfflineHours},
                policy.version = policy.version + 1,
                policy.updated_by = #{updatedBy},
                policy.updated_at = CURRENT_TIMESTAMP
            WHERE organization.organization_key = #{organizationKey}
              AND policy.version = #{expectedVersion}
            """)
    int updateDevicePolicy(@Param("organizationKey") String organizationKey,
                           @Param("expectedVersion") Long expectedVersion,
                           @Param("approvalMode") String approvalMode,
                           @Param("credentialValidityDays") int credentialValidityDays,
                           @Param("maxOfflineHours") int maxOfflineHours,
                           @Param("updatedBy") Long updatedBy);

    @Select("""
            SELECT device.*, member.user_id AS owner_user_id
            FROM device
            JOIN organization ON organization.id = device.organization_id
            LEFT JOIN organization_member member ON member.id = device.member_id
            WHERE organization.organization_key = #{organizationKey}
              AND device.device_key = #{deviceKey}
            LIMIT 1
            """)
    DeviceRecord selectDeviceByKey(@Param("organizationKey") String organizationKey,
                                   @Param("deviceKey") String deviceKey);

    @Select("""
            SELECT device.*, member.user_id AS owner_user_id
            FROM device
            JOIN organization ON organization.id = device.organization_id
            LEFT JOIN organization_member member ON member.id = device.member_id
            WHERE organization.organization_key = #{organizationKey}
              AND device.id = #{deviceId}
            FOR UPDATE
            """)
    DeviceRecord selectDeviceForUpdate(@Param("organizationKey") String organizationKey,
                                       @Param("deviceId") Long deviceId);

    @Select("""
            SELECT device.*, member.user_id AS owner_user_id
            FROM device
            JOIN organization ON organization.id = device.organization_id
            LEFT JOIN organization_member member ON member.id = device.member_id
            WHERE organization.organization_key = #{organizationKey}
              AND device.id = #{deviceId}
            LIMIT 1
            """)
    DeviceRecord selectDeviceById(@Param("organizationKey") String organizationKey,
                                  @Param("deviceId") Long deviceId);

    @Select("""
            SELECT device.*, member.user_id AS owner_user_id
            FROM device
            JOIN organization ON organization.id = device.organization_id
            LEFT JOIN organization_member member ON member.id = device.member_id
            WHERE organization.organization_key = #{organizationKey}
            ORDER BY device.id DESC
            LIMIT #{limit}
            """)
    List<DeviceRecord> selectDevices(@Param("organizationKey") String organizationKey,
                                     @Param("limit") int limit);

    @Insert("""
            INSERT INTO device (
                organization_id, member_id, device_key, platform, display_name,
                app_version, capabilities_json, status, last_seen_at, created_at, updated_at
            ) VALUES (
                #{device.organizationId}, #{device.memberId}, #{device.deviceKey},
                #{device.platform}, #{device.displayName}, #{device.appVersion},
                #{device.capabilitiesJson}, #{device.status}, CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "device.id")
    int insertDevice(@Param("device") DeviceRecord device);

    @Update("""
            UPDATE device
            SET platform = #{platform}, display_name = #{displayName},
                app_version = #{appVersion}, capabilities_json = #{capabilitiesJson},
                rejection_reason = NULL, last_seen_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{deviceId}
            """)
    int updateDeviceMetadata(@Param("deviceId") Long deviceId,
                             @Param("platform") String platform,
                             @Param("displayName") String displayName,
                             @Param("appVersion") String appVersion,
                             @Param("capabilitiesJson") String capabilitiesJson);

    @Update("""
            UPDATE device
            SET status = 'ACTIVE', approved_by = #{approvedBy}, approved_at = #{approvedAt},
                rejection_reason = NULL, revoked_by = NULL, revoked_at = NULL,
                updated_at = #{approvedAt}
            WHERE id = #{deviceId} AND status IN ('PENDING', 'ACTIVE', 'REJECTED')
            """)
    int approveDevice(@Param("deviceId") Long deviceId,
                      @Param("approvedBy") Long approvedBy,
                      @Param("approvedAt") LocalDateTime approvedAt);

    @Update("""
            UPDATE device
            SET status = 'REJECTED', rejection_reason = #{reason}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{deviceId} AND status IN ('PENDING', 'REJECTED')
            """)
    int rejectPendingDevice(@Param("deviceId") Long deviceId,
                            @Param("reason") String reason);

    @Update("""
            UPDATE device
            SET rejection_reason = #{reason}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{deviceId} AND status = 'ACTIVE'
            """)
    int recordRotationRejection(@Param("deviceId") Long deviceId,
                                @Param("reason") String reason);

    @Update("""
            UPDATE device
            SET status = 'REVOKED', revoked_by = #{revokedBy}, revoked_at = #{revokedAt},
                updated_at = #{revokedAt}
            WHERE id = #{deviceId} AND status != 'REVOKED'
            """)
    int revokeDevice(@Param("deviceId") Long deviceId,
                     @Param("revokedBy") Long revokedBy,
                     @Param("revokedAt") LocalDateTime revokedAt);

    @Insert("""
            INSERT INTO device_credential (
                device_id, credential_id, algorithm, public_key, fingerprint, status,
                requested_at
            ) VALUES (
                #{credential.deviceId}, #{credential.credentialId}, #{credential.algorithm},
                #{credential.publicKey}, #{credential.fingerprint}, 'PENDING',
                #{credential.requestedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "credential.id")
    int insertCredential(@Param("credential") DeviceCredentialRecord credential);

    @Select("""
            SELECT * FROM device_credential
            WHERE device_id = #{deviceId} AND status = 'PENDING'
            ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    DeviceCredentialRecord selectPendingCredentialForUpdate(@Param("deviceId") Long deviceId);

    @Select("""
            SELECT * FROM device_credential
            WHERE device_id = #{deviceId}
            ORDER BY CASE status WHEN 'PENDING' THEN 0 WHEN 'ACTIVE' THEN 1 ELSE 2 END, id DESC
            LIMIT 1
            """)
    DeviceCredentialRecord selectLatestCredential(@Param("deviceId") Long deviceId);

    @Select("""
            SELECT * FROM device_credential
            WHERE device_id = #{deviceId} AND fingerprint = #{fingerprint}
              AND (status = 'PENDING' OR (status = 'ACTIVE' AND expires_at > NOW()))
            ORDER BY id DESC LIMIT 1
            """)
    DeviceCredentialRecord selectUsableCredentialByFingerprint(
            @Param("deviceId") Long deviceId,
            @Param("fingerprint") String fingerprint);

    @Select("""
            SELECT COUNT(*) FROM device_credential
            WHERE device_id = #{deviceId} AND status = 'ACTIVE' AND revoked_at IS NULL
            """)
    int countActiveCredentials(@Param("deviceId") Long deviceId);

    @Update("""
            UPDATE device_credential
            SET status = 'ACTIVE', certificate_payload = #{payload},
                certificate_signature = #{signature},
                control_key_fingerprint = #{controlKeyFingerprint},
                approved_by = #{approvedBy}, issued_at = #{issuedAt}, expires_at = #{expiresAt},
                revoked_at = NULL
            WHERE id = #{credentialId} AND status = 'PENDING'
            """)
    int activateCredential(@Param("credentialId") Long credentialId,
                           @Param("payload") String payload,
                           @Param("signature") String signature,
                           @Param("controlKeyFingerprint") String controlKeyFingerprint,
                           @Param("approvedBy") Long approvedBy,
                           @Param("issuedAt") LocalDateTime issuedAt,
                           @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE device_credential
            SET status = 'REVOKED', revoked_at = #{revokedAt}
            WHERE device_id = #{deviceId} AND id != #{exceptCredentialId}
              AND status IN ('PENDING', 'ACTIVE')
            """)
    int revokeOtherCredentials(@Param("deviceId") Long deviceId,
                               @Param("exceptCredentialId") Long exceptCredentialId,
                               @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE device_credential
            SET status = 'REVOKED', revoked_at = #{revokedAt}
            WHERE device_id = #{deviceId} AND status = 'PENDING'
            """)
    int revokePendingCredentials(@Param("deviceId") Long deviceId,
                                 @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE device_credential
            SET status = 'REVOKED', revoked_at = #{revokedAt}
            WHERE id = #{credentialId} AND status = 'PENDING'
            """)
    int revokeCredential(@Param("credentialId") Long credentialId,
                         @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE device_credential
            SET status = 'REVOKED', revoked_at = #{revokedAt}
            WHERE device_id = #{deviceId} AND status IN ('PENDING', 'ACTIVE')
            """)
    int revokeAllCredentials(@Param("deviceId") Long deviceId,
                             @Param("revokedAt") LocalDateTime revokedAt);

    @Select("""
            SELECT id, device_id, user_id, legacy_device_login_id, status, expires_at
            FROM device_session
            WHERE access_token_hash = #{accessTokenHash}
            LIMIT 1
            """)
    DeviceSessionRecord selectSessionByAccessHash(
            @Param("accessTokenHash") String accessTokenHash);

    @Insert("""
            INSERT INTO device_session (
                organization_id, device_id, user_id, legacy_device_login_id,
                access_token_hash, refresh_token_hash, status, issued_at, expires_at
            )
            SELECT organization.id, #{deviceId}, login.user_id, login.id,
                   #{accessTokenHash}, SHA2(login.refresh_token, 256), 'ACTIVE',
                   login.login_time, login.expire_time
            FROM organization
            JOIN device_login login ON login.user_id = #{userId}
            WHERE organization.organization_key = #{organizationKey}
              AND login.token = #{rawAccessToken}
              AND login.status = 1
              AND login.expire_time > CURRENT_TIMESTAMP
            """)
    int insertSessionBinding(@Param("organizationKey") String organizationKey,
                             @Param("deviceId") Long deviceId,
                             @Param("userId") Long userId,
                             @Param("rawAccessToken") String rawAccessToken,
                             @Param("accessTokenHash") String accessTokenHash);

    @Select("""
            SELECT legacy_device_login_id
            FROM device_session
            WHERE device_id = #{deviceId} AND status = 'ACTIVE'
              AND legacy_device_login_id IS NOT NULL
            ORDER BY legacy_device_login_id
            """)
    List<Long> selectActiveLegacySessionIds(@Param("deviceId") Long deviceId);

    @Select("""
            SELECT device.id
            FROM device
            JOIN organization ON organization.id = device.organization_id
            JOIN organization_member member ON member.id = device.member_id
            WHERE organization.organization_key = #{organizationKey}
              AND member.user_id = #{userId}
              AND device.status != 'REVOKED'
            ORDER BY device.id
            """)
    List<Long> selectRevocableDeviceIdsByUser(@Param("organizationKey") String organizationKey,
                                              @Param("userId") Long userId);

    @Update("""
            UPDATE device_login login
            JOIN device_session session ON session.legacy_device_login_id = login.id
            SET login.status = 0
            WHERE session.device_id = #{deviceId} AND login.status = 1
            """)
    int revokeLegacySessions(@Param("deviceId") Long deviceId);

    @Update("""
            UPDATE device_session
            SET status = 'REVOKED', revoked_at = #{revokedAt}
            WHERE device_id = #{deviceId} AND status = 'ACTIVE'
            """)
    int revokeDeviceSessions(@Param("deviceId") Long deviceId,
                             @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE organization_policy
            SET revocation_version = #{version}, version = version + 1,
                updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{policyId} AND revocation_version = #{previousVersion}
            """)
    int updateRevocationVersion(@Param("policyId") Long policyId,
                                @Param("previousVersion") Long previousVersion,
                                @Param("version") Long version,
                                @Param("updatedBy") Long updatedBy);

    @Insert("""
            INSERT INTO revocation_entry (
                organization_id, subject_type, subject_id, version, reason,
                signed_payload, signature, control_key_fingerprint,
                revoked_by, revoked_at, expires_at
            ) VALUES (
                #{entry.organizationId}, #{entry.subjectType}, #{entry.subjectId},
                #{entry.version}, #{entry.reason}, #{entry.signedPayload},
                #{entry.signature}, #{entry.controlKeyFingerprint}, #{entry.revokedBy},
                #{entry.revokedAt}, #{entry.expiresAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "entry.id")
    int insertRevocation(@Param("entry") RevocationEntryRecord entry);

    @Select("""
            SELECT revocation.*
            FROM revocation_entry revocation
            JOIN organization ON organization.id = revocation.organization_id
            WHERE organization.organization_key = #{organizationKey}
              AND revocation.version > #{afterVersion}
            ORDER BY revocation.version
            LIMIT #{limit}
            """)
    List<RevocationEntryRecord> selectRevocations(@Param("organizationKey") String organizationKey,
                                                  @Param("afterVersion") long afterVersion,
                                                  @Param("limit") int limit);
}
