/**
 * LanChat — utils.js
 * 通用工具函数
 */

const Utils = {
  /**
   * 显示 Toast 通知
   * @param {string} message - 消息内容
   * @param {string} type - 类型: success / error / warn / default
   * @param {number} duration - 显示时长（毫秒）
   */
  toast(message, type = 'default', duration = 3000) {
    const container = document.getElementById('toastContainer');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = `toast ${type === 'default' ? '' : 'toast-' + type}`;
    toast.textContent = message;
    container.appendChild(toast);
    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transition = 'opacity 200ms ease';
      setTimeout(() => toast.remove(), 200);
    }, duration);
  },

  /**
   * 格式化时间
   * @param {string|Date} datetime - 时间
   * @param {boolean} showTime - 是否显示时分
   * @returns {string}
   */
  formatTime(datetime, showTime = true) {
    if (!datetime) return '';
    const date = new Date(datetime);
    if (isNaN(date.getTime())) return '';
    const now = new Date();
    const diff = now - date;
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const msgDay = new Date(date.getFullYear(), date.getMonth(), date.getDate());

    const hh = String(date.getHours()).padStart(2, '0');
    const mm = String(date.getMinutes()).padStart(2, '0');

    if (msgDay.getTime() === today.getTime()) {
      return showTime ? `${hh}:${mm}` : '今天';
    }
    if (diff < 7 * 24 * 60 * 60 * 1000 && msgDay.getTime() > today.getTime() - 7 * 24 * 60 * 60 * 1000) {
      const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
      return showTime ? `${days[date.getDay()]} ${hh}:${mm}` : days[date.getDay()];
    }
    const mo = String(date.getMonth() + 1).padStart(2, '0');
    const dd = String(date.getDate()).padStart(2, '0');
    return showTime ? `${mo}/${dd} ${hh}:${mm}` : `${mo}/${dd}`;
  },

  /**
   * 格式化文件大小
   * @param {number} bytes - 字节数
   * @returns {string}
   */
  formatFileSize(bytes) {
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0;
    while (bytes >= 1024 && i < units.length - 1) {
      bytes /= 1024;
      i++;
    }
    return `${bytes.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
  },

  /**
   * 获取头像首字母
   * @param {string} name - 名称
   * @returns {string}
   */
  getInitials(name) {
    if (!name) return '?';
    return name.charAt(0).toUpperCase();
  },

  /**
   * 根据名字生成颜色
   * @param {string} name - 名称
   * @returns {string}
   */
  getColorFromName(name) {
    if (!name) return '#1A1D21';
    const colors = [
      '#059669', '#0891B2', '#7C3AED', '#C026D3',
      '#DC2626', '#EA580C', '#CA8A04', '#16A34A',
      '#2563EB', '#475569'
    ];
    let hash = 0;
    for (let i = 0; i < name.length; i++) {
      hash = name.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  },

  /**
   * 生成头像 HTML
   * @param {string} name - 名称
   * @param {string} avatarUrl - 头像 URL
   * @param {string} size - 尺寸: sm / md / lg
   * @param {boolean} online - 是否在线
   * @returns {string}
   */
  avatarHTML(name, avatarUrl, size = 'md', online = false) {
    const initials = this.getInitials(name);
    const color = this.getColorFromName(name);
    const onlineClass = online ? ' avatar-online' : '';
    if (avatarUrl) {
      return `<div class="avatar avatar-${size}${onlineClass}">
        <img src="${avatarUrl}" alt="${name}" style="width:100%;height:100%;object-fit:cover;border-radius:inherit;">
      </div>`;
    }
    return `<div class="avatar avatar-${size}${onlineClass}" style="background:${color}">${initials}</div>`;
  },

  /**
   * 转义 HTML
   * @param {string} text - 原始文本
   * @returns {string}
   */
  escapeHTML(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  },

  /**
   * 解析消息内容中的 @ 提及
   * @param {string} content - 消息内容
   * @param {Array} mentionIds - 提及的用户 ID 数组
   * @param {Object} userMap - 用户 ID 到昵称的映射
   * @returns {string}
   */
  parseMentions(content, mentionIds, userMap) {
    if (!content) return '';
    // 先转义 HTML，然后处理 @提及
    let html = this.escapeHTML(content);
    if (mentionIds && mentionIds.length > 0) {
      mentionIds.forEach(id => {
        const name = userMap[id] || id;
        const regex = new RegExp(`@${this.escapeHTML(name)}`, 'g');
        html = html.replace(regex, `<span class="mention-tag">@${this.escapeHTML(name)}</span>`);
      });
    }
    // 自动识别 URL 并转为可点击链接（在新标签页打开）
    const urlRegex = /(https?:\/\/[^\s<]+|(?:www\.)[^\s<]+)/gi;
    html = html.replace(urlRegex, (url) => {
      const href = url.startsWith('www.') ? 'http://' + url : url;
      return `<a href="${href}" target="_blank" rel="noopener noreferrer" class="msg-link" onclick="event.stopPropagation()">${url}</a>`;
    });
    return html;
  },

  /**
   * 防抖
   * @param {Function} fn - 函数
   * @param {number} delay - 延迟（毫秒）
   * @returns {Function}
   */
  debounce(fn, delay = 300) {
    let timer = null;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), delay);
    };
  },

  /**
   * 深拷贝
   * @param {*} obj - 对象
   * @returns {*}
   */
  deepClone(obj) {
    return JSON.parse(JSON.stringify(obj));
  },

  /**
   * 生成 UUID
   * @returns {string}
   */
  uuid() {
    return Date.now().toString(36) + Math.random().toString(36).substring(2, 10);
  },

  /**
   * 判断是否为图片类型
   * @param {string} type - 文件类型
   * @returns {boolean}
   */
  isImage(type) {
    return ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'].includes((type || '').toLowerCase());
  },

  /**
   * 获取文件扩展名
   * @param {string} filename - 文件名
   * @returns {string}
   */
  getFileExt(filename) {
    if (!filename) return '';
    return filename.split('.').pop().toLowerCase();
  },

  /**
   * 本地存储封装
   */
  storage: {
    get(key) {
      const val = localStorage.getItem('lanchat_' + key);
      try {
        return val ? JSON.parse(val) : null;
      } catch {
        return val;
      }
    },
    set(key, val) {
      localStorage.setItem('lanchat_' + key, typeof val === 'string' ? val : JSON.stringify(val));
    },
    remove(key) {
      localStorage.removeItem('lanchat_' + key);
    },
    clear() {
      Object.keys(localStorage).forEach(k => {
        if (k.startsWith('lanchat_')) localStorage.removeItem(k);
      });
    }
  }
};
