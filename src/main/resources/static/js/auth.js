/**
 * LanChat — auth.js
 * 登录/注册逻辑
 */

/**
 * 切换登录/注册标签
 */
function switchTab(tab) {
  document.querySelectorAll('.auth-tab').forEach(el => {
    el.classList.toggle('active', el.dataset.tab === tab);
  });
  document.getElementById('loginForm').classList.toggle('hidden', tab !== 'login');
  document.getElementById('registerForm').classList.toggle('hidden', tab !== 'register');
  document.getElementById('loginError').classList.remove('show');
  document.getElementById('registerError').classList.remove('show');
}

/**
 * 显示错误信息
 */
function showAuthError(form, message) {
  const errorEl = document.getElementById(form + 'Error');
  errorEl.textContent = message;
  errorEl.classList.add('show');
}

/**
 * 处理登录
 */
async function handleLogin(event) {
  event.preventDefault();
  const username = document.getElementById('loginUsername').value.trim();
  const password = document.getElementById('loginPassword').value;
  const btn = document.getElementById('loginBtn');

  if (!username || !password) {
    showAuthError('login', '请输入用户名和密码');
    return;
  }

  btn.disabled = true;
  btn.textContent = '登录中...';

  try {
    const data = await API.auth.login(username, password);
    if (data) {
      Utils.storage.set('token', data.token);
      Utils.storage.set('refreshToken', data.refreshToken);
      Utils.storage.set('userId', data.userId);
      Utils.storage.set('userInfo', {
        userId: data.userId,
        username: data.username,
        nickname: data.nickname,
        avatar: data.avatar
      });
      Utils.storage.set('expiresIn', data.expiresIn);
      Utils.toast('登录成功', 'success', 1500);
      setTimeout(() => {
        window.location.href = '/chat';
      }, 500);
    } else {
      showAuthError('login', '用户名或密码错误');
    }
  } catch (err) {
    showAuthError('login', '登录失败，请稍后重试');
  } finally {
    btn.disabled = false;
    btn.textContent = '登录';
  }
}

/**
 * 处理注册
 */
async function handleRegister(event) {
  event.preventDefault();
  const username = document.getElementById('regUsername').value.trim();
  const password = document.getElementById('regPassword').value;
  const nickname = document.getElementById('regNickname').value.trim();
  const btn = document.getElementById('registerBtn');

  // 前端校验
  if (!username) {
    showAuthError('register', '请输入用户名');
    return;
  }
  if (password.length < 8 || password.length > 20) {
    showAuthError('register', '密码长度需 8-20 位');
    return;
  }
  if (!/(?=.*[a-zA-Z])(?=.*\d)/.test(password)) {
    showAuthError('register', '密码需包含字母和数字');
    return;
  }
  if (nickname.length < 2 || nickname.length > 16) {
    showAuthError('register', '昵称长度需 2-16 个字符');
    return;
  }

  btn.disabled = true;
  btn.textContent = '注册中...';

  try {
    const result = await API.auth.register(username, password, nickname);
    if (result !== null) {
      // 注册成功，自动登录
      btn.textContent = '正在进入...';
      const loginData = await API.auth.login(username, password);
      if (loginData) {
        Utils.storage.set('token', loginData.token);
        Utils.storage.set('refreshToken', loginData.refreshToken);
        Utils.storage.set('userId', loginData.userId);
        Utils.storage.set('userInfo', {
          userId: loginData.userId,
          username: loginData.username,
          nickname: loginData.nickname,
          avatar: loginData.avatar
        });
        Utils.storage.set('expiresIn', loginData.expiresIn);
        Utils.toast('注册成功！', 'success', 1500);
        setTimeout(() => {
          window.location.href = '/welcome';
        }, 500);
        return;
      }
      // 自动登录失败则回退到手动登录
      Utils.toast('注册成功，请登录', 'success');
      switchTab('login');
      document.getElementById('loginUsername').value = username;
      document.getElementById('loginPassword').focus();
    } else {
      showAuthError('register', '注册失败，用户名可能已存在');
    }
  } catch (err) {
    showAuthError('register', '注册失败，请稍后重试');
  } finally {
    btn.disabled = false;
    btn.textContent = '注册';
  }
}
