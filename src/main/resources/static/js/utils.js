/**
 * LanChat — utils.js
 * 通用工具函数
 */

// 全局默认 SVG 头像定义
window.DEFAULT_AVATARS = [
  { name: '小猫', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a1" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#FFB347"/><stop offset="100%" stop-color="#FF9500"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a1)"/><path d="M30 50 L42 22 L54 44" fill="#FF8C00" opacity=".8"/><path d="M90 50 L78 22 L66 44" fill="#FF8C00" opacity=".8"/><circle cx="46" cy="62" r="5" fill="#5D3A1A"/><circle cx="74" cy="62" r="5" fill="#5D3A1A"/><ellipse cx="60" cy="74" rx="4" ry="3" fill="#E8756D"/><path d="M56 78Q60 84 64 78" stroke="#5D3A1A" stroke-width="2" fill="none" stroke-linecap="round"/><line x1="28" y1="68" x2="42" y2="70" stroke="#5D3A1A" stroke-width="1.5" opacity=".5"/><line x1="28" y1="74" x2="42" y2="73" stroke="#5D3A1A" stroke-width="1.5" opacity=".5"/><line x1="78" y1="70" x2="92" y2="68" stroke="#5D3A1A" stroke-width="1.5" opacity=".5"/><line x1="78" y1="73" x2="92" y2="74" stroke="#5D3A1A" stroke-width="1.5" opacity=".5"/></svg>' },
  { name: '鲸鱼', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a2" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#B8E4F9"/><stop offset="100%" stop-color="#5AC8FA"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a2)"/><ellipse cx="60" cy="68" rx="36" ry="26" fill="#4AB8E8"/><circle cx="46" cy="62" r="4" fill="#1A3A5C"/><path d="M52 78Q60 84 68 78" stroke="#1A3A5C" stroke-width="2.5" fill="none" stroke-linecap="round"/><path d="M54 38Q58 26 62 38" stroke="#4AB8E8" stroke-width="3" fill="none" stroke-linecap="round"/><circle cx="58" cy="28" r="3" fill="#4AB8E8"/><ellipse cx="88" cy="72" rx="6" ry="10" fill="#4AB8E8" transform="rotate(20 88 72)"/></svg>' },
  { name: '兔子', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a3" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#FFB6C8"/><stop offset="100%" stop-color="#FF2D55"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a3)"/><ellipse cx="46" cy="28" rx="8" ry="22" fill="#FFD6E0" transform="rotate(-8 46 28)"/><ellipse cx="46" cy="28" rx="4" ry="16" fill="#FF8FAB" transform="rotate(-8 46 28)"/><ellipse cx="74" cy="28" rx="8" ry="22" fill="#FFD6E0" transform="rotate(8 74 28)"/><ellipse cx="74" cy="28" rx="4" ry="16" fill="#FF8FAB" transform="rotate(8 74 28)"/><circle cx="60" cy="72" r="28" fill="#FFD6E0"/><circle cx="48" cy="66" r="4.5" fill="#5D3A1A"/><circle cx="72" cy="66" r="4.5" fill="#5D3A1A"/><ellipse cx="60" cy="76" rx="3.5" ry="3" fill="#FF8FAB"/><circle cx="42" cy="78" r="6" fill="#FF8FAB" opacity=".4"/><circle cx="78" cy="78" r="6" fill="#FF8FAB" opacity=".4"/></svg>' },
  { name: '青蛙', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a4" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#A8F0C0"/><stop offset="100%" stop-color="#34C759"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a4)"/><circle cx="42" cy="46" r="16" fill="#E8F8EE"/><circle cx="78" cy="46" r="16" fill="#E8F8EE"/><circle cx="42" cy="46" r="9" fill="#2D8E42"/><circle cx="78" cy="46" r="9" fill="#2D8E42"/><circle cx="44" cy="44" r="3.5" fill="white"/><circle cx="80" cy="44" r="3.5" fill="white"/><path d="M44 82Q60 92 76 82" stroke="#2D6E38" stroke-width="2.5" fill="none" stroke-linecap="round"/><circle cx="58" cy="72" r="1.5" fill="#2D6E38"/><circle cx="64" cy="72" r="1.5" fill="#2D6E38"/></svg>' },
  { name: '猫头鹰', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a5" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#D4A5F5"/><stop offset="100%" stop-color="#AF52DE"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a5)"/><path d="M34 40L26 20L46 36" fill="#9340BF"/><path d="M86 40L94 20L74 36" fill="#9340BF"/><circle cx="44" cy="60" r="16" fill="#E8D4F5"/><circle cx="76" cy="60" r="16" fill="#E8D4F5"/><circle cx="44" cy="60" r="9" fill="#3D1A5C"/><circle cx="76" cy="60" r="9" fill="#3D1A5C"/><circle cx="47" cy="57" r="3.5" fill="white"/><circle cx="79" cy="57" r="3.5" fill="white"/><path d="M56 74L60 80L64 74" fill="#FF9500"/></svg>' },
  { name: '小鸡', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a6" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#FFF3B0"/><stop offset="100%" stop-color="#FFCC00"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a6)"/><circle cx="60" cy="64" r="32" fill="#FFE066"/><circle cx="48" cy="58" r="4" fill="#5D3A1A"/><circle cx="72" cy="58" r="4" fill="#5D3A1A"/><path d="M56 68L60 74L64 68" fill="#FF9500"/><circle cx="40" cy="72" r="7" fill="#FFB3B3" opacity=".5"/><circle cx="80" cy="72" r="7" fill="#FFB3B3" opacity=".5"/><path d="M50 32Q56 22 60 30Q64 22 70 32" fill="#FFD633"/></svg>' },
  { name: '狐狸', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a7" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#80E8E0"/><stop offset="100%" stop-color="#00C7BE"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a7)"/><path d="M30 56L38 22L52 48" fill="#00B0A8" opacity=".8"/><path d="M90 56L82 22L68 48" fill="#00B0A8" opacity=".8"/><ellipse cx="60" cy="72" rx="30" ry="24" fill="#E0FAF8"/><path d="M38 68Q60 58 82 68Q82 90 60 94Q38 90 38 68" fill="#E0FAF8"/><circle cx="48" cy="66" r="3" fill="#1A4A46"/><circle cx="72" cy="66" r="3" fill="#1A4A46"/><ellipse cx="60" cy="76" rx="4" ry="3" fill="#1A4A46"/></svg>' },
  { name: '小熊', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a8" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#FFB0A0"/><stop offset="100%" stop-color="#FF6B6B"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a8)"/><circle cx="34" cy="40" r="14" fill="#FF8F80"/><circle cx="34" cy="40" r="8" fill="#FFC0B8"/><circle cx="86" cy="40" r="14" fill="#FF8F80"/><circle cx="86" cy="40" r="8" fill="#FFC0B8"/><circle cx="60" cy="68" r="30" fill="#FFC0B8"/><circle cx="48" cy="62" r="4.5" fill="#5D3A1A"/><circle cx="72" cy="62" r="4.5" fill="#5D3A1A"/><ellipse cx="60" cy="72" rx="6" ry="5" fill="#E88080"/><circle cx="60" cy="70" r="3" fill="#5D3A1A"/></svg>' },
  { name: '企鹅', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a9" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#7B7AE8"/><stop offset="100%" stop-color="#5856D6"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a9)"/><ellipse cx="60" cy="70" rx="32" ry="36" fill="#3D3BB0"/><ellipse cx="60" cy="78" rx="20" ry="24" fill="#E8E8F8"/><circle cx="48" cy="58" r="4" fill="white"/><circle cx="48" cy="58" r="2.5" fill="#1A1A3C"/><circle cx="72" cy="58" r="4" fill="white"/><circle cx="72" cy="58" r="2.5" fill="#1A1A3C"/><path d="M56 70L60 76L64 70" fill="#FF9500"/></svg>' },
  { name: '火烈鸟', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a10" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#FF6B8A"/><stop offset="100%" stop-color="#C8214A"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a10)"/><circle cx="66" cy="42" r="18" fill="#FFB0C4"/><path d="M66 60Q58 80 54 98" stroke="#FFB0C4" stroke-width="6" fill="none" stroke-linecap="round"/><path d="M54 98Q50 102 46 98" stroke="#FFB0C4" stroke-width="4" fill="none" stroke-linecap="round"/><circle cx="72" cy="38" r="3" fill="#3D1A28"/><path d="M78 44L86 40L78 48" fill="#FF9500"/></svg>' },
  { name: '考拉', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a11" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#C8C8CC"/><stop offset="100%" stop-color="#8E8E93"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a11)"/><circle cx="34" cy="48" r="18" fill="#B0B0B5"/><circle cx="34" cy="48" r="10" fill="#D8D8DC"/><circle cx="86" cy="48" r="18" fill="#B0B0B5"/><circle cx="86" cy="48" r="10" fill="#D8D8DC"/><circle cx="60" cy="68" r="28" fill="#D8D8DC"/><path d="M50 60Q48 56 52 58" stroke="#3A3A3C" stroke-width="2" fill="none" stroke-linecap="round"/><path d="M70 60Q72 56 68 58" stroke="#3A3A3C" stroke-width="2" fill="none" stroke-linecap="round"/><ellipse cx="60" cy="72" rx="8" ry="6" fill="#3A3A3C"/></svg>' },
  { name: '狮子', svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><defs><linearGradient id="a12" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#FFD060"/><stop offset="100%" stop-color="#FF9F0A"/></linearGradient></defs><rect width="120" height="120" rx="28" fill="url(#a12)"/><circle cx="60" cy="62" r="36" fill="#E8960A"/><circle cx="60" cy="68" r="26" fill="#FFD680"/><circle cx="48" cy="62" r="4" fill="#5D3A1A"/><circle cx="72" cy="62" r="4" fill="#5D3A1A"/><ellipse cx="60" cy="72" rx="4" ry="3" fill="#5D3A1A"/><path d="M52 78Q60 84 68 78" stroke="#5D3A1A" stroke-width="2" fill="none" stroke-linecap="round"/></svg>' }
];

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
      // SVG 内置头像：svg:index
      if (avatarUrl.startsWith('svg:')) {
        const idx = parseInt(avatarUrl.split(':')[1]) || 0;
        const av = (window.DEFAULT_AVATARS || [])[idx];
        if (av) {
          return `<div class="avatar avatar-${size}${onlineClass}" style="padding:0;overflow:hidden;background:none;">${av.svg}</div>`;
        }
      }
      // Emoji 头像：emoji:emoji:color
      if (avatarUrl.startsWith('emoji:')) {
        const parts = avatarUrl.split(':');
        const emoji = parts[1];
        const bg = parts[2] || '#007AFF';
        return `<div class="avatar avatar-${size}${onlineClass}" style="background:${bg}">${emoji}</div>`;
      }
      // URL 头像
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
