/**
 * LanChat — chat.js
 * 主聊天应用逻辑
 */

const App = {
  // 当前状态
  currentView: 'chats',         // chats / contacts / groups / requests
  currentChat: null,            // { type: 'private'|'group', id, name, avatar, online }
  currentUser: null,            // 当前登录用户
  friends: [],                  // 好友列表
  groups: [],                   // 群组列表
  friendRequests: [],           // 好友申请列表
  messages: [],                 // 当前会话消息列表
  onlineUserIds: new Set(),     // 在线用户 ID 集合
  userMap: {},                  // userId -> { nickname, avatar } 映射

  // 输入状态
  isBurnMode: false,
  replyTo: null,                // 回复的消息对象
  typingTimer: null,
  isTypingSent: false,

  // 文件上传状态
  uploadingFiles: new Map(),    // 正在上传的文件

  /**
   * 初始化应用
   */
  async init() {
    // 检查登录状态
    const token = Utils.storage.get('token');
    if (!token) {
      window.location.href = '/index.html';
      return;
    }

    this.currentUser = Utils.storage.get('userInfo');
    if (!this.currentUser) {
      const userInfo = await API.user.getInfo();
      if (!userInfo) {
        window.location.href = '/index.html';
        return;
      }
      this.currentUser = {
        userId: userInfo.id,
        username: userInfo.username,
        nickname: userInfo.nickname,
        avatar: userInfo.avatar
      };
      Utils.storage.set('userInfo', this.currentUser);
    }

    // 渲染导航栏头像
    this.renderNavAvatar();
    this.renderSidebarUserAvatar();

    // 设置 WebSocket
    this.setupWebSocket();

    // 连接 WebSocket
    WS.connect(this.currentUser.userId);

    // 加载数据
    await this.loadFriends();
    await this.loadGroups();
    await this.loadFriendRequests();

    // 渲染侧边栏
    this.renderSidebar();

    // 检测移动端
    this.detectMobile();
  },

  // ==================== WebSocket 设置 ====================

  setupWebSocket() {
    WS.on('open', () => {
      Utils.toast('已连接', 'success', 1500);
    });

    WS.on('close', () => {
      Utils.toast('连接断开，正在重连...', 'warn');
    });

    WS.on('chat', (msg) => {
      this.handleIncomingMessage(msg);
    });

    WS.on('recall', (msg) => {
      this.handleRecallMessage(msg);
    });

    WS.on('burn', (msg) => {
      this.handleBurnMessage(msg);
    });

    WS.on('read', (msg) => {
      this.handleReadReceipt(msg);
    });

    WS.on('typing', (msg) => {
      this.handleTypingIndicator(msg);
    });

    WS.on('screenshot', (msg) => {
      Utils.toast(msg.content || '对方可能已截屏', 'warn', 5000);
    });

    WS.on('system', (msg) => {
      this.handleSystemMessage(msg);
    });

    WS.on('onlineList', (msg) => {
      this.handleOnlineList(msg);
    });

    WS.on('friend', async (msg) => {
      // 好友相关事件：刷新好友列表和申请列表
      await this.loadFriends();
      await this.loadFriendRequests();
      this.renderSidebar();
      if (msg.content) {
        Utils.toast(msg.content, 'success', 4000);
      }
    });

    WS.on('error', (msg) => {
      if (msg && msg.content) {
        Utils.toast(msg.content, 'error');
      }
    });
  },

  // ==================== 数据加载 ====================

  async loadFriends() {
    const data = await API.friend.getList();
    if (data) {
      this.friends = data;
      // 构建 userMap
      data.forEach(f => {
        this.userMap[f.friendId] = {
          nickname: f.remark || f.nickname || f.username,
          avatar: f.avatar
        };
      });
    }
  },

  async loadGroups() {
    const data = await API.group.getMy();
    if (data) {
      this.groups = data;
    }
  },

  async loadFriendRequests() {
    const data = await API.friend.getRequests();
    if (data) {
      this.friendRequests = data.filter(r => r.status === 0);
      this.updateRequestBadge();
    }
  },

  // ==================== 导航与视图 ====================

  switchView(view) {
    this.currentView = view;

    // 更新导航栏
    document.querySelectorAll('.nav-rail-item').forEach(el => {
      el.classList.toggle('active', el.dataset.view === view);
    });
    document.querySelectorAll('.mobile-nav-item').forEach((el, i) => {
      const views = ['chats', 'contacts', 'groups', 'requests', 'profile'];
      el.classList.toggle('active', views[i] === view);
    });

    // 更新标题
    const titles = {
      chats: '消息',
      contacts: '好友',
      groups: '群组',
      requests: '好友申请'
    };
    document.getElementById('sidebarTitle').textContent = titles[view] || '消息';

    // 创建群组按钮只在 contacts 视图显示
    document.getElementById('createGroupBtn').style.display = view === 'contacts' ? '' : 'none';

    // 渲染侧边栏列表
    this.renderSidebar();
  },

  /**
   * 渲染侧边栏列表
   */
  renderSidebar() {
    const container = document.getElementById('sidebarList');
    const keyword = document.getElementById('sidebarSearch').value.trim().toLowerCase();

    if (this.currentView === 'chats') {
      this.renderConversationList(container, keyword);
    } else if (this.currentView === 'contacts') {
      this.renderContactsList(container, keyword);
    } else if (this.currentView === 'groups') {
      this.renderGroupsList(container, keyword);
    } else if (this.currentView === 'requests') {
      this.renderRequestsList(container, keyword);
    }
  },

  /**
   * 渲染会话列表（消息视图）
   */
  renderConversationList(container, keyword) {
    // 合并好友和群组，按最后消息时间排序
    const convos = [];

    this.friends.forEach(f => {
      const name = f.remark || f.nickname || f.username;
      if (keyword && !name.toLowerCase().includes(keyword)) return;
      convos.push({
        type: 'private',
        id: f.friendId,
        name,
        avatar: f.avatar,
        online: this.onlineUserIds.has(f.friendId),
        lastMsg: f.lastMessage || '',
        lastTime: f.lastMessageTime || '',
        isPinned: f.isPinned === 1,
        isMuted: f.isMuted === 1,
        isBlocked: f.isBlocked === 1
      });
    });

    this.groups.forEach(g => {
      if (keyword && !g.groupName.toLowerCase().includes(keyword)) return;
      convos.push({
        type: 'group',
        id: g.id,
        name: g.groupName,
        avatar: g.avatar,
        online: false,
        lastMsg: '',
        lastTime: '',
        isPinned: false,
        isMuted: false
      });
    });

    // 排序：置顶在前，然后按时间
    convos.sort((a, b) => {
      if (a.isPinned !== b.isPinned) return b.isPinned - a.isPinned;
      return new Date(b.lastTime || 0) - new Date(a.lastTime || 0);
    });

    if (convos.length === 0) {
      container.innerHTML = `<div style="padding:2rem 1rem;text-align:center;color:var(--color-stone);font-size:var(--fs-sm);">暂无会话</div>`;
      return;
    }

    container.innerHTML = convos.map(c => `
      <div class="conv-item ${this.currentChat && this.currentChat.type === c.type && this.currentChat.id === c.id ? 'active' : ''}"
           onclick="App.openConversation('${c.type}', ${c.id}, '${Utils.escapeHTML(c.name)}', '${c.avatar || ''}', ${c.online})"
           style="animation:fadeInUp 200ms ease forwards;">
        ${Utils.avatarHTML(c.name, c.avatar, 'md', c.online)}
        <div class="conv-item-info">
          <div class="conv-item-top">
            <div class="conv-item-name">${Utils.escapeHTML(c.name)}</div>
            ${c.lastTime ? `<div class="conv-item-time">${Utils.formatTime(c.lastTime, false)}</div>` : ''}
          </div>
          <div class="conv-item-bottom">
            <div class="conv-item-msg ${c.isBlocked ? 'burn' : ''}">${c.isBlocked ? '已拉黑' : (c.lastMsg ? Utils.escapeHTML(c.lastMsg) : '开始聊天')}</div>
            <div style="display:flex;gap:0.25rem;align-items:center;">
              ${c.isPinned ? '<span class="conv-item-pin"><svg width="0.75rem" height="0.75rem" viewBox="0 0 24 24" fill="currentColor"><path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5.2v6h1.6v-6H18v-2l-2-2z"/></svg></span>' : ''}
              ${c.isMuted ? '<span class="conv-item-muted"><svg width="0.75rem" height="0.75rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg></span>' : ''}
            </div>
          </div>
        </div>
      </div>
    `).join('');
  },

  /**
   * 渲染好友列表
   */
  renderContactsList(container, keyword) {
    let friends = this.friends;
    if (keyword) {
      friends = friends.filter(f => {
        const name = (f.remark || f.nickname || f.username).toLowerCase();
        return name.includes(keyword);
      });
    }

    if (friends.length === 0) {
      container.innerHTML = `<div style="padding:2rem 1rem;text-align:center;color:var(--color-stone);font-size:var(--fs-sm);">${keyword ? '未找到匹配的好友' : '暂无好友，点击右上角搜索添加'}</div>`;
      return;
    }

    container.innerHTML = friends.map((f, i) => {
      const name = f.remark || f.nickname || f.username;
      const online = this.onlineUserIds.has(f.friendId);
      return `
        <div class="list-item" style="animation:fadeInUp 200ms ease ${i * 30}ms forwards;opacity:0;"
             onclick="App.openConversation('private', ${f.friendId}, '${Utils.escapeHTML(name)}', '${f.avatar || ''}', ${online})">
          ${Utils.avatarHTML(name, f.avatar, 'md', online)}
          <div class="list-item-info">
            <div class="list-item-name">${Utils.escapeHTML(name)} ${f.isBlocked === 1 ? '<span class="text-stone" style="font-size:var(--fs-xs);">(已拉黑)</span>' : ''}</div>
            <div class="list-item-desc">${f.signature || '暂无签名'}</div>
          </div>
          <div class="list-item-actions">
            <div class="btn-icon" onclick="event.stopPropagation();App.showFriendActions(${f.friendId})" title="更多">
              <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/></svg>
            </div>
          </div>
        </div>
      `;
    }).join('');
  },

  /**
   * 渲染群组列表
   */
  renderGroupsList(container, keyword) {
    let groups = this.groups;
    if (keyword) {
      groups = groups.filter(g => g.groupName.toLowerCase().includes(keyword));
    }

    if (groups.length === 0) {
      container.innerHTML = `<div style="padding:2rem 1rem;text-align:center;color:var(--color-stone);font-size:var(--fs-sm);">${keyword ? '未找到匹配的群组' : '暂无群组，去好友列表创建一个吧'}</div>`;
      return;
    }

    container.innerHTML = groups.map((g, i) => `
      <div class="list-item" style="animation:fadeInUp 200ms ease ${i * 30}ms forwards;opacity:0;"
           onclick="App.openConversation('group', ${g.id}, '${Utils.escapeHTML(g.groupName)}', '${g.avatar || ''}', false)">
        ${Utils.avatarHTML(g.groupName, g.avatar, 'md', false)}
        <div class="list-item-info">
          <div class="list-item-name">${Utils.escapeHTML(g.groupName)}</div>
          <div class="list-item-desc">${g.announcement || '暂无公告'}</div>
        </div>
      </div>
    `).join('');
  },

  /**
   * 渲染好友申请列表
   */
  renderRequestsList(container, keyword) {
    let requests = this.friendRequests;
    if (requests.length === 0) {
      container.innerHTML = `<div style="padding:2rem 1rem;text-align:center;color:var(--color-stone);font-size:var(--fs-sm);">暂无好友申请</div>`;
      return;
    }

    container.innerHTML = requests.map((r, i) => `
      <div class="list-item" style="animation:fadeInUp 200ms ease ${i * 30}ms forwards;opacity:0;">
        ${Utils.avatarHTML(r.fromUsername || '用户', null, 'md', false)}
        <div class="list-item-info">
          <div class="list-item-name">${Utils.escapeHTML(r.fromUsername || '未知用户')}</div>
          <div class="list-item-desc">${Utils.escapeHTML(r.message || '请求添加你为好友')}</div>
        </div>
        <div style="display:flex;gap:0.5rem;">
          <button class="btn btn-primary" style="padding:0.375rem 0.75rem;min-height:auto;font-size:var(--fs-sm);"
                  onclick="App.handleFriendRequest(${r.id}, true)">接受</button>
          <button class="btn btn-secondary" style="padding:0.375rem 0.75rem;min-height:auto;font-size:var(--fs-sm);"
                  onclick="App.handleFriendRequest(${r.id}, false)">拒绝</button>
        </div>
      </div>
    `).join('');
  },

  // ==================== 会话操作 ====================

  /**
   * 打开会话
   */
  openConversation(type, id, name, avatar, online) {
    this.currentChat = { type, id, name, avatar, online };

    // 更新 UI
    document.getElementById('chatEmpty').classList.add('hidden');
    document.getElementById('chatActive').classList.remove('hidden');
    document.getElementById('chatActive').style.display = 'flex';

    // 渲染头部
    document.getElementById('chatHeaderAvatar').innerHTML = Utils.avatarHTML(name, avatar, 'sm', type === 'private' && online);
    document.getElementById('chatHeaderName').textContent = name;
    this.updateChatHeaderStatus(type, online);

    // 清空消息区
    document.getElementById('messageArea').innerHTML = '';
    this.messages = [];

    // 加载历史消息
    this.loadMessages();

    // 移动端：隐藏侧边栏
    if (window.innerWidth <= 768) {
      document.getElementById('sidebar').classList.add('hidden-mobile');
      document.querySelector('.mobile-back').style.display = '';
    }

    // 重新渲染侧边栏以更新 active 状态
    this.renderSidebar();

    // 关闭信息面板
    document.getElementById('infoPanel').classList.remove('show');

    // 重置输入状态
    this.isBurnMode = false;
    this.replyTo = null;
    this.updateBurnToggle();
    this.cancelReply();
  },

  /**
   * 更新聊天头部状态
   */
  updateChatHeaderStatus(type, online) {
    const statusEl = document.getElementById('chatHeaderStatus');
    if (type === 'private') {
      statusEl.textContent = online ? '在线' : '离线';
      statusEl.className = 'chat-header-status' + (online ? ' online' : '');
    } else {
      statusEl.textContent = '群组';
      statusEl.className = 'chat-header-status';
    }
  },

  /**
   * 加载历史消息
   */
  async loadMessages() {
    if (!this.currentChat) return;

    let history;
    if (this.currentChat.type === 'private') {
      history = await API.chat.getPrivateHistory(this.currentUser.userId, this.currentChat.id);
    } else {
      history = await API.chat.getGroupHistory(this.currentChat.id);
    }

    if (history) {
      this.messages = history;
      this.renderMessages();

      // 标记已读
      if (this.currentChat.type === 'private') {
        API.chat.markAsRead(this.currentChat.id, this.currentUser.userId);
      }
    }
  },

  /**
   * 渲染消息列表
   */
  renderMessages() {
    const container = document.getElementById('messageArea');
    container.innerHTML = '';

    let lastDate = '';

    this.messages.forEach(msg => {
      // 日期分隔符
      const msgDate = msg.createTime ? new Date(msg.createTime).toDateString() : '';
      if (msgDate && msgDate !== lastDate) {
        lastDate = msgDate;
        const dateEl = document.createElement('div');
        dateEl.className = 'msg-date-sep';
        dateEl.textContent = Utils.formatTime(msg.createTime, false);
        container.appendChild(dateEl);
      }

      container.appendChild(this.createMessageElement(msg));
    });

    // 滚动到底部
    container.scrollTop = container.scrollHeight;
  },

  /**
   * 创建消息元素
   */
  createMessageElement(msg) {
    const isSelf = msg.fromUserId === this.currentUser.userId;
    const row = document.createElement('div');
    row.className = `msg-row ${isSelf ? 'self' : 'peer'}`;
    row.dataset.messageId = msg.messageId;

    // 发送者信息
    let senderName = '';
    let senderAvatar = '';
    if (!isSelf) {
      if (this.currentChat.type === 'group') {
        const userInfo = this.userMap[msg.fromUserId];
        senderName = userInfo ? userInfo.nickname : '用户' + msg.fromUserId;
        senderAvatar = userInfo ? userInfo.avatar : null;
      } else {
        senderName = this.currentChat.name;
        senderAvatar = this.currentChat.avatar;
      }
    } else {
      senderName = this.currentUser.nickname;
      senderAvatar = this.currentUser.avatar;
    }

    // 头像
    const avatarHTML = Utils.avatarHTML(
      isSelf ? this.currentUser.nickname : senderName,
      isSelf ? this.currentUser.avatar : senderAvatar,
      'sm',
      false
    );

    // 消息内容
    let bubbleHTML = '';
    let bubbleClass = '';

    if (msg.isRecalled === 1) {
      bubbleClass = 'recalled';
      bubbleHTML = '消息已撤回';
    } else if (msg.status === 2) {
      bubbleClass = 'burned';
      bubbleHTML = '消息已焚毁';
    } else {
      const contentType = msg.type || 'text';
      if (contentType === 'image') {

        let imageData;
        try {
          imageData = JSON.parse(msg.content);
        } catch (e) {
          imageData = { url: msg.content, originalUrl: msg.content };
        }

        // 显示缩略图（如果有），点击查看原图
        const displayUrl = imageData.thumbnailUrl || imageData.url;
        const originalUrl = imageData.originalUrl || imageData.url;

        bubbleHTML = `
        <div style="position:relative;display:inline-block;">
            <img src="${displayUrl}" 
                 alt="图片" 
                 onclick="App.previewImage('${originalUrl}')"
                 style="max-width:300px;max-height:300px;border-radius:8px;cursor:pointer;">
            ${displayUrl !== originalUrl ? `
                <div style="position:absolute;bottom:4px;right:4px;
                            background:rgba(0,0,0,0.6);color:white;
                            padding:2px 8px;border-radius:4px;font-size:10px;
                            pointer-events:none;">
                    查看原图
                </div>
            ` : ''}
        </div>
    `;

        bubbleClass = 'type-image';
      } else if (contentType === 'file') {
        bubbleClass = 'type-file';
        const fileInfo = JSON.parse(msg.content || '{}');
        const downloadUrl = fileInfo.url || '';
        bubbleHTML = `
          <div class="msg-file-bubble" onclick="App.downloadFile('${Utils.escapeHTML(downloadUrl)}', '${Utils.escapeHTML(fileInfo.name || '文件')}')" title="点击下载">
            <div class="msg-file-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></div>
            <div class="msg-file-info">
              <div class="msg-file-name">${Utils.escapeHTML(fileInfo.name || '文件')}</div>
              <div class="msg-file-size">${Utils.formatFileSize(fileInfo.size)}</div>
            </div>
          </div>
        `;
      } else {
        // 文本消息
        const mentionIds = msg.mentionUserIds ? msg.mentionUserIds.split(',').filter(Boolean) : [];
        bubbleHTML = Utils.parseMentions(msg.content || '', mentionIds, this.userMap);

        // 阅后即焚标记
        if (msg.isBurn === 1) {
          bubbleClass = 'burn-pending';
        }

        // 引用回复
        if (msg.replyToId) {
          const repliedMsg = this.messages.find(m => m.messageId === msg.replyToId);
          if (repliedMsg) {
            const replySender = repliedMsg.fromUserId === this.currentUser.userId
              ? this.currentUser.nickname
              : (this.userMap[repliedMsg.fromUserId]?.nickname || '用户');
            const replyContent = repliedMsg.isRecalled ? '消息已撤回' :
              (repliedMsg.status === 2 ? '消息已焚毁' : repliedMsg.content);
            bubbleHTML = `
              <div class="msg-reply-preview">
                <div class="msg-reply-sender">${Utils.escapeHTML(replySender)}</div>
                <div class="msg-reply-content">${Utils.escapeHTML(replyContent || '')}</div>
              </div>
              ${bubbleHTML}
            `;
          }
        }
      }
    }

    // 消息操作按钮（仅自己发送的且未撤回未焚毁的消息）
    let actionsHTML = '';
    if (isSelf && msg.isRecalled !== 1 && msg.status !== 2) {
      const canRecall = msg.createTime && (Date.now() - new Date(msg.createTime).getTime() < 2 * 60 * 1000);
      actionsHTML = `
        <div class="msg-actions">
          <div class="msg-action-btn" onclick="App.setReply('${msg.messageId}')">回复</div>
          ${canRecall ? `<div class="msg-action-btn danger" onclick="App.recallMessage('${msg.messageId}')">撤回</div>` : ''}
        </div>
      `;
    } else if (!isSelf && msg.isRecalled !== 1 && msg.status !== 2) {
      actionsHTML = `
        <div class="msg-actions">
          <div class="msg-action-btn" onclick="App.setReply('${msg.messageId}')">回复</div>
          ${msg.isBurn === 1 ? `<div class="msg-action-btn danger" onclick="App.burnMessage('${msg.messageId}')">焚毁</div>` : ''}
        </div>
      `;
    }

    row.innerHTML = `
      <div class="msg-avatar">${avatarHTML}</div>
      <div class="msg-content">
        ${!isSelf && this.currentChat.type === 'group' ? `<div class="msg-sender">${Utils.escapeHTML(senderName)}</div>` : ''}
        <div class="msg-bubble ${bubbleClass}">
          ${actionsHTML}
          ${bubbleHTML}
        </div>
        <div class="msg-time">${Utils.formatTime(msg.createTime)}</div>
      </div>
    `;

    return row;
  },

  // ==================== 消息发送 ====================

  /**
   * 处理输入
   */
  handleInput(textarea) {
    // 自动高度
    textarea.style.height = 'auto';
    textarea.style.height = Math.min(textarea.scrollHeight, 128) + 'px';

    // 正在输入提示
    if (!this.currentChat) return;
    if (!this.isTypingSent) {
      this.isTypingSent = true;
      if (this.currentChat.type === 'private') {
        WS.sendTyping(this.currentChat.id, null);
      } else {
        WS.sendTyping(null, this.currentChat.id);
      }
      clearTimeout(this.typingTimer);
      this.typingTimer = setTimeout(() => {
        this.isTypingSent = false;
      }, 3000);
    }
  },

  /**
   * 处理按键
   */
  handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.sendMessage();
    }
  },

  /**
   * 发送消息
   */
  sendMessage() {
    if (!this.currentChat) return;
    const input = document.getElementById('messageInput');
    const content = input.value.trim();
    if (!content) return;

    const messageId = Utils.uuid();
    const msgData = {
      messageId,
      contentType: 'text',
      content,
      replyToId: this.replyTo ? this.replyTo.messageId : null,
      isBurn: this.isBurnMode,
      burnDuration: 5
    };

    if (this.currentChat.type === 'private') {
      msgData.toUserId = this.currentChat.id;
    } else {
      msgData.groupId = this.currentChat.id;
    }

    const sent = WS.sendChat(msgData);
    if (sent) {
      // 清空输入
      input.value = '';
      input.style.height = 'auto';

      // 取消回复
      this.cancelReply();

      // 取消阅后即焚模式（发送后重置）
      if (this.isBurnMode) {
        this.isBurnMode = false;
        this.updateBurnToggle();
      }
    }
  },

  // ==================== WebSocket 消息处理 ====================

  /**
   * 处理收到的聊天消息
   */
  handleIncomingMessage(msg) {
    // 判断是否属于当前会话
    let isCurrentChat = false;
    const hasChat = this.currentChat && this.currentChat.id;
    if (hasChat) {
      isCurrentChat =
        (msg.groupId && this.currentChat.type === 'group' && msg.groupId === this.currentChat.id) ||
        (!msg.groupId && this.currentChat.type === 'private' &&
         ((msg.fromUserId === this.currentChat.id) || (msg.toUserId === this.currentChat.id && msg.fromUserId === this.currentUser.userId)));
    }

    if (isCurrentChat) {
      // 转换为 ChatMessage 格式
      const chatMsg = {
        messageId: msg.messageId,
        fromUserId: msg.fromUserId,
        toUserId: msg.toUserId,
        groupId: msg.groupId,
        type: msg.contentType,
        content: msg.content,
        replyToId: msg.replyToId,
        mentionUserIds: msg.mentionUserIds,
        isBurn: msg.isBurn ? 1 : 0,
        burnDuration: msg.burnDuration,
        isRecalled: 0,
        status: 0,
        createTime: msg.timestamp
      };

      // 避免重复
      if (!this.messages.find(m => m.messageId === msg.messageId)) {
        this.messages.push(chatMsg);
        const container = document.getElementById('messageArea');
        container.appendChild(this.createMessageElement(chatMsg));
        container.scrollTop = container.scrollHeight;

        // 阅后即焚消息：开始倒计时
        if (msg.isBurn && msg.fromUserId !== this.currentUser.userId) {
          this.startBurnCountdown(msg.messageId, msg.burnDuration || 5);
        }

        // 自动标记已读
        if (this.currentChat.type === 'private' && msg.fromUserId !== this.currentUser.userId) {
          API.chat.markAsRead(msg.fromUserId, this.currentUser.userId);
        }
      }
    }

    // 无论是否在当前会话，都更新侧边栏会话列表
    // 确保用户能看到新消息的预览和未读提示
    this.updateConversationPreview(msg);
  },

  /**
   * 处理撤回消息
   */
  handleRecallMessage(msg) {
    const target = this.messages.find(m => m.messageId === msg.messageId);
    if (target) {
      target.isRecalled = 1;
      this.renderMessages();
    }
    Utils.toast('一条消息被撤回', 'default', 2000);
  },

  /**
   * 处理焚毁消息
   */
  handleBurnMessage(msg) {
    const target = this.messages.find(m => m.messageId === msg.messageId);
    if (target) {
      target.status = 2;
      target.content = '';
      this.renderMessages();
    }
  },

  /**
   * 处理已读回执
   */
  handleReadReceipt(msg) {
    // 更新消息状态为已读
    this.messages.forEach(m => {
      if (m.fromUserId === msg.toUserId && m.toUserId === msg.fromUserId && m.status === 0) {
        m.status = 1;
      }
    });
  },

  /**
   * 处理正在输入
   */
  handleTypingIndicator(msg) {
    if (!this.currentChat) return;
    const isCurrentChat =
      (msg.groupId && this.currentChat.type === 'group' && msg.groupId === this.currentChat.id) ||
      (!msg.groupId && this.currentChat.type === 'private' && msg.fromUserId === this.currentChat.id);

    if (isCurrentChat && msg.fromUserId !== this.currentUser.userId) {
      const indicator = document.getElementById('typingIndicator');
      indicator.classList.remove('hidden');
      const textEl = document.getElementById('typingText');
      if (msg.fromNickname) {
        textEl.textContent = `${msg.fromNickname} 正在输入...`;
      } else {
        textEl.textContent = '对方正在输入...';
      }

      clearTimeout(this.typingHideTimer);
      this.typingHideTimer = setTimeout(() => {
        indicator.classList.add('hidden');
      }, 3000);
    }
  },

  /**
   * 处理系统消息
   */
  handleSystemMessage(msg) {
    // 在消息区显示系统消息
    if (this.currentChat) {
      const container = document.getElementById('messageArea');
      const sysEl = document.createElement('div');
      sysEl.className = 'msg-system';
      sysEl.textContent = msg.content;
      container.appendChild(sysEl);
      container.scrollTop = container.scrollHeight;
    }

    // 上线/下线 更新在线状态
    if (msg.content && msg.content.includes('上线')) {
      this.onlineUserIds.add(msg.fromUserId);
    } else if (msg.content && msg.content.includes('下线')) {
      this.onlineUserIds.delete(msg.fromUserId);
    }
    this.renderSidebar();
  },

  /**
   * 处理在线用户列表
   */
  handleOnlineList(msg) {
    try {
      const users = JSON.parse(msg.content || '[]');
      this.onlineUserIds.clear();
      users.forEach(u => this.onlineUserIds.add(u.id));
      this.renderSidebar();
    } catch (e) {
      console.error('Failed to parse online list:', e);
    }
  },

  // ==================== 消息操作 ====================

  /**
   * 撤回消息
   */
  async recallMessage(messageId) {
    const success = await API.chat.recall(messageId);
    if (success !== null) {
      WS.sendRecall(messageId);
      const target = this.messages.find(m => m.messageId === messageId);
      if (target) {
        target.isRecalled = 1;
        this.renderMessages();
      }
      Utils.toast('消息已撤回', 'success');
    }
  },

  /**
   * 焚毁消息
   */
  async burnMessage(messageId) {
    const success = await API.chat.burn(messageId);
    if (success !== null) {
      WS.sendBurn(messageId);
      const target = this.messages.find(m => m.messageId === messageId);
      if (target) {
        target.status = 2;
        target.content = '';
        this.renderMessages();
      }
    }
  },

  /**
   * 开始焚毁倒计时
   */
  startBurnCountdown(messageId, duration) {
    setTimeout(() => {
      const target = this.messages.find(m => m.messageId === messageId);
      if (target && target.status !== 2) {
        WS.sendBurn(messageId);
        target.status = 2;
        target.content = '';
        this.renderMessages();
      }
    }, duration * 1000);
  },

  /**
   * 设置回复
   */
  setReply(messageId) {
    const msg = this.messages.find(m => m.messageId === messageId);
    if (!msg) return;
    this.replyTo = msg;
    const previewEl = document.getElementById('replyPreview');
    const textEl = document.getElementById('replyPreviewText');
    const sender = msg.fromUserId === this.currentUser.userId
      ? this.currentUser.nickname
      : (this.userMap[msg.fromUserId]?.nickname || this.currentChat.name);
    const content = msg.isRecalled ? '消息已撤回' : (msg.status === 2 ? '消息已焚毁' : (msg.content || ''));
    textEl.textContent = `${sender}: ${content}`;
    previewEl.classList.remove('hidden');
    document.getElementById('messageInput').focus();
  },

  /**
   * 取消回复
   */
  cancelReply() {
    this.replyTo = null;
    document.getElementById('replyPreview').classList.add('hidden');
  },

  /**
   * 切换阅后即焚
   */
  toggleBurn() {
    this.isBurnMode = !this.isBurnMode;
    this.updateBurnToggle();
    if (this.isBurnMode) {
      Utils.toast('阅后即焚已开启', 'warn', 2000);
    }
  },

  updateBurnToggle() {
    document.getElementById('burnToggle').classList.toggle('active', this.isBurnMode);
  },

  // ==================== 文件上传 ====================

  /**
   * 处理文件上传
   */
  async handleFileUpload(input, type) {
    const file = input.files[0];
    if (!file) return;
    input.value = '';

    if (!this.currentChat) return;

    Utils.toast('正在上传...', 'default', 2000);

    try {
      const formData = new FormData();
      formData.append('file', file);
      const result = await API.file.upload(formData);

      if (result) {
        const fileInfo = {
          name: result.originalName || file.name,
          size: result.fileSize || file.size,
          url: result.url,
          thumbnailUrl: result.thumbnailUrl
        };

        const messageId = Utils.uuid();
        const contentType = type === 'image' ? 'image' : 'file';
        const content = type === 'image'
          ? JSON.stringify({ url: result.url, thumbnailUrl: result.thumbnailUrl, originalUrl: result.url })
          : JSON.stringify(fileInfo);

        const msgData = {
          messageId,
          contentType,
          content,
          isBurn: false
        };

        if (this.currentChat.type === 'private') {
          msgData.toUserId = this.currentChat.id;
        } else {
          msgData.groupId = this.currentChat.id;
        }

        WS.sendChat(msgData);
        Utils.toast('文件已发送', 'success', 1500);
      }
    } catch (err) {
      Utils.toast('文件上传失败', 'error');
    }
  },

  /**
   * 预览图片
   */
  previewImage(url) {
    const modal = document.createElement('div');
    modal.className = 'modal-overlay';
    modal.onclick = () => modal.remove();
    modal.innerHTML = `
      <div style="max-width:90vw;max-height:90vh;" onclick="event.stopPropagation()">
        <img src="${url}" alt="预览" style="max-width:90vw;max-height:90vh;border-radius:var(--r-xl);">
      </div>
    `;
    document.body.appendChild(modal);
  },

  // ==================== 好友管理 ====================

  /**
   * 处理好友申请
   */
  async handleFriendRequest(requestId, accept) {
    await API.friend.handleRequest(requestId, accept);
    this.friendRequests = this.friendRequests.filter(r => r.id !== requestId);
    this.updateRequestBadge();
    this.renderSidebar();
    Utils.toast(accept ? '已添加好友' : '已拒绝申请', 'success');

    if (accept) {
      await this.loadFriends();
    }
  },

  /**
   * 更新申请徽章
   */
  updateRequestBadge() {
    const badge = document.getElementById('requestBadge');
    const count = this.friendRequests.length;
    if (count > 0) {
      badge.textContent = count;
      badge.classList.remove('hidden');
    } else {
      badge.classList.add('hidden');
    }
  },

  /**
   * 显示好友操作菜单
   */
  showFriendActions(friendId) {
    const friend = this.friends.find(f => f.friendId === friendId);
    if (!friend) return;

    const name = friend.remark || friend.nickname || friend.username;
    const bodyHTML = `
      <div class="info-section-title">好友操作</div>
      <div class="info-action-list">
        <div class="info-action" onclick="App.togglePinFriend(${friendId})">
          <span class="info-action-label">${friend.isPinned === 1 ? '取消置顶' : '置顶'}</span>
        </div>
        <div class="info-action" onclick="App.toggleMuteFriend(${friendId})">
          <span class="info-action-label">${friend.isMuted === 1 ? '取消免打扰' : '免打扰'}</span>
        </div>
        <div class="info-action" onclick="App.showSetRemarkModal(${friendId}, '${Utils.escapeHTML(friend.remark || '')}')">
          <span class="info-action-label">设置备注</span>
        </div>
        <div class="info-action" onclick="App.toggleBlockFriend(${friendId})">
          <span class="info-action-label ${friend.isBlocked === 1 ? 'text-danger' : ''}">${friend.isBlocked === 1 ? '取消拉黑' : '拉黑'}</span>
        </div>
        <div class="info-action" onclick="App.deleteFriend(${friendId})">
          <span class="info-action-label text-danger">删除好友</span>
        </div>
      </div>
    `;

    this.showInfoPanel(name, bodyHTML);
  },

  async togglePinFriend(friendId) {
    await API.friend.togglePin(friendId);
    await this.loadFriends();
    this.renderSidebar();
    Utils.toast('操作成功', 'success');
  },

  async toggleMuteFriend(friendId) {
    await API.friend.toggleMute(friendId);
    await this.loadFriends();
    this.renderSidebar();
    Utils.toast('操作成功', 'success');
  },

  async toggleBlockFriend(friendId) {
    await API.friend.toggleBlock(friendId);
    await this.loadFriends();
    this.renderSidebar();
    this.closeInfoPanel();
    Utils.toast('操作成功', 'success');
  },

  async deleteFriend(friendId) {
    if (!confirm('确定删除该好友吗？')) return;
    await API.friend.delete(friendId);
    await this.loadFriends();
    this.renderSidebar();
    this.closeInfoPanel();
    Utils.toast('已删除好友', 'success');
  },

  /**
   * 搜索用户并发送好友申请
   */
  showSearchModal() {
    const modalHTML = `
      <div class="modal-overlay" id="searchModal" onclick="if(event.target===this)App.closeModal('searchModal')">
        <div class="modal">
          <div class="modal-header">
            <div class="modal-title">搜索用户</div>
            <div class="btn-icon" onclick="App.closeModal('searchModal')">
              <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </div>
          </div>
          <div class="modal-body">
            <div class="search-box" style="margin-bottom:1rem;">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
              <input type="text" id="searchUserInput" placeholder="输入用户名搜索" oninput="App.searchUsers(this.value)">
            </div>
            <div id="searchResults" style="min-height:4rem;">
              <div class="text-slate text-center" style="padding:1rem;font-size:var(--fs-sm);">输入用户名开始搜索</div>
            </div>
          </div>
        </div>
      </div>
    `;
    document.getElementById('modalContainer').innerHTML = modalHTML;
  },

  searchUsers: Utils.debounce(async function(keyword) {
    if (!keyword || keyword.trim().length < 1) {
      document.getElementById('searchResults').innerHTML = '<div class="text-slate text-center" style="padding:1rem;font-size:var(--fs-sm);">输入用户名开始搜索</div>';
      return;
    }
    const users = await API.user.search(keyword.trim());
    const container = document.getElementById('searchResults');
    if (!users || users.length === 0) {
      container.innerHTML = '<div class="text-slate text-center" style="padding:1rem;font-size:var(--fs-sm);">未找到用户</div>';
      return;
    }
    container.innerHTML = users.map(u => {
      const name = u.nickname || u.username;
      const isFriend = App.friends.some(f => f.friendId === u.id);
      const isSelf = u.id === App.currentUser.userId;
      return `
        <div class="list-item">
          ${Utils.avatarHTML(name, u.avatar, 'md', u.online === 1)}
          <div class="list-item-info">
            <div class="list-item-name">${Utils.escapeHTML(name)}</div>
            <div class="list-item-desc">@${Utils.escapeHTML(u.username)}</div>
          </div>
          ${isSelf ? '<span class="text-stone" style="font-size:var(--fs-sm);">自己</span>' :
            isSelf || isFriend ? '<span class="text-stone" style="font-size:var(--fs-sm);">已是好友</span>' :
            `<button class="btn btn-primary" style="padding:0.375rem 0.75rem;min-height:auto;font-size:var(--fs-sm);" onclick="App.sendFriendRequest(${u.id}, '${Utils.escapeHTML(name)}')">添加</button>`}
        </div>
      `;
    }).join('');
  }, 300),

  async sendFriendRequest(userId, name) {
    const msg = prompt(`向 ${name} 发送好友申请`, '你好，我想添加你为好友');
    if (msg === null) return;
    const result = await API.friend.sendRequest(userId, msg || '');
    if (result !== null) {
      Utils.toast('好友申请已发送', 'success');
      App.closeModal('searchModal');
    }
  },

  showSetRemarkModal(friendId, currentRemark) {
    const modalHTML = `
      <div class="modal-overlay" id="remarkModal" onclick="if(event.target===this)App.closeModal('remarkModal')">
        <div class="modal">
          <div class="modal-header">
            <div class="modal-title">设置备注</div>
            <div class="btn-icon" onclick="App.closeModal('remarkModal')">
              <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </div>
          </div>
          <div class="modal-body">
            <div class="input-group">
              <input type="text" id="remarkInput" class="input-field" placeholder="输入备注名" value="${Utils.escapeHTML(currentRemark)}">
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" onclick="App.closeModal('remarkModal')">取消</button>
            <button class="btn btn-primary" onclick="App.saveRemark(${friendId})">保存</button>
          </div>
        </div>
      </div>
    `;
    document.getElementById('modalContainer').innerHTML = modalHTML;
    document.getElementById('remarkInput').focus();
  },

  async saveRemark(friendId) {
    const remark = document.getElementById('remarkInput').value.trim();
    await API.friend.setRemark(friendId, remark);
    await this.loadFriends();
    this.renderSidebar();
    this.closeModal('remarkModal');
    this.closeInfoPanel();
    Utils.toast('备注已更新', 'success');
  },

  // ==================== 群组管理 ====================

  /**
   * 显示创建群组弹窗
   */
  showCreateGroupModal() {
    const friendOptions = this.friends.map(f => {
      const name = f.remark || f.nickname || f.username;
      return `
        <label class="list-item" style="cursor:pointer;">
          <input type="checkbox" value="${f.friendId}" class="group-member-checkbox" style="margin-right:0.5rem;">
          ${Utils.avatarHTML(name, f.avatar, 'sm', false)}
          <div class="list-item-info">
            <div class="list-item-name">${Utils.escapeHTML(name)}</div>
          </div>
        </label>
      `;
    }).join('');

    const modalHTML = `
      <div class="modal-overlay" id="createGroupModal" onclick="if(event.target===this)App.closeModal('createGroupModal')">
        <div class="modal">
          <div class="modal-header">
            <div class="modal-title">创建群组</div>
            <div class="btn-icon" onclick="App.closeModal('createGroupModal')">
              <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </div>
          </div>
          <div class="modal-body">
            <div class="input-group" style="margin-bottom:1rem;">
              <label class="input-label">群名称</label>
              <input type="text" id="groupNameInput" class="input-field" placeholder="2-20 个字符" maxlength="20">
            </div>
            <div class="input-group">
              <label class="input-label">选择成员</label>
              <div style="max-height:16rem;overflow-y:auto;border:1px solid var(--color-border);border-radius:var(--r-lg);padding:0.25rem;">
                ${friendOptions || '<div class="text-slate text-center" style="padding:1rem;font-size:var(--fs-sm);">暂无好友可选择</div>'}
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" onclick="App.closeModal('createGroupModal')">取消</button>
            <button class="btn btn-primary" onclick="App.createGroup()">创建</button>
          </div>
        </div>
      </div>
    `;
    document.getElementById('modalContainer').innerHTML = modalHTML;
    document.getElementById('groupNameInput').focus();
  },

  async createGroup() {
    const groupName = document.getElementById('groupNameInput').value.trim();
    if (groupName.length < 2 || groupName.length > 20) {
      Utils.toast('群名称需 2-20 个字符', 'error');
      return;
    }

    const memberIds = Array.from(document.querySelectorAll('.group-member-checkbox:checked'))
      .map(cb => parseInt(cb.value));

    const result = await API.group.create(groupName, memberIds);
    if (result) {
      Utils.toast('群组创建成功', 'success');
      this.closeModal('createGroupModal');
      await this.loadGroups();
      this.switchView('groups');
      this.openConversation('group', result.id, result.groupName, result.avatar, false);
    }
  },

  // ==================== 信息面板 ====================

  showInfoPanel(title, bodyHTML) {
    document.getElementById('infoPanelTitle').textContent = title;
    document.getElementById('infoPanelBody').innerHTML = bodyHTML;
    document.getElementById('infoPanel').classList.add('show');
  },

  closeInfoPanel() {
    document.getElementById('infoPanel').classList.remove('show');
  },

  toggleInfoPanel() {
    const panel = document.getElementById('infoPanel');
    if (panel.classList.contains('show')) {
      this.closeInfoPanel();
    } else {
      this.showCurrentChatInfo();
    }
  },

  showCurrentChatInfo() {
    if (!this.currentChat) return;

    if (this.currentChat.type === 'private') {
      const friend = this.friends.find(f => f.friendId === this.currentChat.id);
      const name = friend ? (friend.remark || friend.nickname || friend.username) : this.currentChat.name;
      const bodyHTML = `
        <div class="info-profile">
          ${Utils.avatarHTML(name, this.currentChat.avatar, 'lg', this.currentChat.online)}
          <div class="info-profile-name">${Utils.escapeHTML(name)}</div>
          ${friend && friend.signature ? `<div class="info-profile-signature">${Utils.escapeHTML(friend.signature)}</div>` : ''}
        </div>
        <div>
          <div class="info-section-title">操作</div>
          <div class="info-action-list">
            <div class="info-action" onclick="App.togglePinFriend(${this.currentChat.id})">
              <span class="info-action-label">${friend && friend.isPinned === 1 ? '取消置顶' : '置顶'}</span>
            </div>
            <div class="info-action" onclick="App.toggleMuteFriend(${this.currentChat.id})">
              <span class="info-action-label">${friend && friend.isMuted === 1 ? '取消免打扰' : '免打扰'}</span>
            </div>
            <div class="info-action" onclick="App.showSetRemarkModal(${this.currentChat.id}, '${Utils.escapeHTML(friend ? friend.remark || '' : '')}')">
              <span class="info-action-label">设置备注</span>
            </div>
            <div class="info-action" onclick="App.toggleBlockFriend(${this.currentChat.id})">
              <span class="info-action-label ${friend && friend.isBlocked === 1 ? 'text-danger' : ''}">${friend && friend.isBlocked === 1 ? '取消拉黑' : '拉黑'}</span>
            </div>
            <div class="info-action" onclick="App.deleteFriend(${this.currentChat.id})">
              <span class="info-action-label text-danger">删除好友</span>
            </div>
          </div>
        </div>
      `;
      this.showInfoPanel(name, bodyHTML);
    } else {
      // 群组信息
      this.showGroupInfo(this.currentChat.id);
    }
  },

  async showGroupInfo(groupId) {
    const [groupInfo, members] = await Promise.all([
      API.group.getById(groupId),
      API.group.getMembers(groupId)
    ]);

    if (!groupInfo) return;

    const isOwner = groupInfo.ownerId === this.currentUser.userId;
    const memberHTML = (members || []).map(m => {
      const name = m.nickname || m.username || '用户' + m.userId;
      const role = m.role === 2 ? 'owner' : (m.role === 1 ? 'admin' : '');
      const roleText = m.role === 2 ? '群主' : (m.role === 1 ? '管理员' : '');
      return `
        <div class="member-item">
          ${Utils.avatarHTML(name, m.avatar, 'sm', false)}
          <div class="member-name">${Utils.escapeHTML(name)}</div>
          ${roleText ? `<span class="member-role ${role}">${roleText}</span>` : ''}
        </div>
      `;
    }).join('');

    const bodyHTML = `
      <div class="info-profile">
        ${Utils.avatarHTML(groupInfo.groupName, groupInfo.avatar, 'lg', false)}
        <div class="info-profile-name">${Utils.escapeHTML(groupInfo.groupName)}</div>
        ${groupInfo.announcement ? `<div class="info-profile-signature">${Utils.escapeHTML(groupInfo.announcement)}</div>` : ''}
      </div>
      <div>
        <div class="info-section-title">群成员 (${members ? members.length : 0})</div>
        <div class="member-list">${memberHTML}</div>
      </div>
      ${isOwner ? `
      <div>
        <div class="info-section-title">群管理</div>
        <div class="info-action-list">
          <div class="info-action" onclick="App.showAddMemberModal(${groupId})">
            <span class="info-action-label">添加成员</span>
          </div>
          <div class="info-action" onclick="App.leaveGroup(${groupId}, true)">
            <span class="info-action-label text-danger">解散群组</span>
          </div>
        </div>
      </div>
      ` : `
      <div>
        <div class="info-section-title">操作</div>
        <div class="info-action-list">
          <div class="info-action" onclick="App.leaveGroup(${groupId}, false)">
            <span class="info-action-label text-danger">退出群组</span>
          </div>
        </div>
      </div>
      `}
    `;
    this.showInfoPanel(groupInfo.groupName, bodyHTML);
  },

  async showAddMemberModal(groupId) {
    const members = await API.group.getMembers(groupId);
    const memberIds = new Set((members || []).map(m => m.userId));
    const availableFriends = this.friends.filter(f => !memberIds.has(f.friendId));

    const friendOptions = availableFriends.map(f => {
      const name = f.remark || f.nickname || f.username;
      return `
        <label class="list-item" style="cursor:pointer;">
          <input type="checkbox" value="${f.friendId}" class="add-member-checkbox" style="margin-right:0.5rem;">
          ${Utils.avatarHTML(name, f.avatar, 'sm', false)}
          <div class="list-item-info">
            <div class="list-item-name">${Utils.escapeHTML(name)}</div>
          </div>
        </label>
      `;
    }).join('');

    const modalHTML = `
      <div class="modal-overlay" id="addMemberModal" onclick="if(event.target===this)App.closeModal('addMemberModal')">
        <div class="modal">
          <div class="modal-header">
            <div class="modal-title">添加群成员</div>
            <div class="btn-icon" onclick="App.closeModal('addMemberModal')">
              <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </div>
          </div>
          <div class="modal-body">
            <div style="max-height:16rem;overflow-y:auto;">
              ${friendOptions || '<div class="text-slate text-center" style="padding:1rem;font-size:var(--fs-sm);">暂无可添加的好友</div>'}
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" onclick="App.closeModal('addMemberModal')">取消</button>
            <button class="btn btn-primary" onclick="App.addMembers(${groupId})">添加</button>
          </div>
        </div>
      </div>
    `;
    document.getElementById('modalContainer').innerHTML = modalHTML;
  },

  async addMembers(groupId) {
    const userIds = Array.from(document.querySelectorAll('.add-member-checkbox:checked'))
      .map(cb => parseInt(cb.value));
    if (userIds.length === 0) {
      Utils.toast('请选择要添加的成员', 'warn');
      return;
    }
    await API.group.addMembers(groupId, userIds);
    Utils.toast('成员已添加', 'success');
    this.closeModal('addMemberModal');
    this.closeInfoPanel();
    this.showGroupInfo(groupId);
  },

  async leaveGroup(groupId, isOwner) {
    if (isOwner) {
      if (!confirm('确定解散该群组吗？此操作不可恢复。')) return;
      await API.group.dissolve(groupId);
      Utils.toast('群组已解散', 'success');
    } else {
      if (!confirm('确定退出该群组吗？')) return;
      await API.group.leave(groupId);
      Utils.toast('已退出群组', 'success');
    }
    this.closeInfoPanel();
    await this.loadGroups();
    this.switchView('groups');
    document.getElementById('chatEmpty').classList.remove('hidden');
    document.getElementById('chatActive').classList.add('hidden');
    document.getElementById('chatActive').style.display = 'none';
    this.currentChat = null;
  },

  // ==================== 个人资料 ====================

  showProfile() {
    const name = this.currentUser.nickname || this.currentUser.username;
    const bodyHTML = `
      <div class="info-profile">
        ${Utils.avatarHTML(name, this.currentUser.avatar, 'lg', true)}
        <div class="info-profile-name">${Utils.escapeHTML(name)}</div>
        <div class="info-profile-signature">@${Utils.escapeHTML(this.currentUser.username)}</div>
      </div>
      <div>
        <div class="info-section-title">操作</div>
        <div class="info-action-list">
          <div class="info-action" onclick="App.showDevices()">
            <span class="info-action-label">设备管理</span>
          </div>
          <div class="info-action" onclick="App.showChangePassword()">
            <span class="info-action-label">修改密码</span>
          </div>
          <div class="info-action" onclick="App.logout()">
            <span class="info-action-label text-danger">退出登录</span>
          </div>
        </div>
      </div>
    `;
    this.showInfoPanel('我的', bodyHTML);
  },

  async showDevices() {
    const devices = await API.user.getDevices();
    const deviceHTML = (devices || []).map(d => `
      <div class="member-item">
        <div class="avatar avatar-sm" style="background:var(--color-canvas);">
          <svg width="1rem" height="1rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>
          </svg>
        </div>
        <div class="member-name">${Utils.escapeHTML(d.deviceName || d.deviceType || '设备')}</div>
        <span class="member-role ${d.status === 1 ? '' : ''}" style="${d.status === 1 ? 'color:var(--color-accent);' : 'color:var(--color-stone);'}">${d.status === 1 ? '在线' : '离线'}</span>
        ${d.status === 1 ? `<div class="btn-icon" onclick="App.logoutDevice(${d.id})" title="下线">
          <svg width="0.875rem" height="0.875rem" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        </div>` : ''}
      </div>
    `).join('');

    const bodyHTML = `
      <div>
        <div class="info-section-title">登录设备</div>
        <div class="member-list">${deviceHTML || '<div class="text-slate text-center" style="padding:1rem;font-size:var(--fs-sm);">暂无设备记录</div>'}</div>
      </div>
    `;
    this.showInfoPanel('设备管理', bodyHTML);
  },

  showChangePassword() {
    const bodyHTML = `
      <div style="padding: 1rem;">
        <div class="input-group" style="margin-bottom: 1rem;">
          <label class="input-label" for="oldPasswordInput">原密码</label>
          <input type="password" id="oldPasswordInput" class="input-field" placeholder="输入原密码">
        </div>
        <div class="input-group" style="margin-bottom: 1rem;">
          <label class="input-label" for="newPasswordInput">新密码</label>
          <input type="password" id="newPasswordInput" class="input-field" placeholder="8-20位，含字母和数字">
        </div>
        <div class="input-group" style="margin-bottom: 1rem;">
          <label class="input-label" for="confirmPasswordInput">确认新密码</label>
          <input type="password" id="confirmPasswordInput" class="input-field" placeholder="再次输入新密码">
        </div>
        <button class="btn btn-primary" style="width:100%;" onclick="App.doChangePassword()">确认修改</button>
      </div>
    `;
    this.showInfoPanel('修改密码', bodyHTML);
  },

  async doChangePassword() {
    const oldPwd = document.getElementById('oldPasswordInput')?.value;
    const newPwd = document.getElementById('newPasswordInput')?.value;
    const confirmPwd = document.getElementById('confirmPasswordInput')?.value;

    if (!oldPwd || !newPwd || !confirmPwd) {
      Utils.toast('请填写所有字段', 'error');
      return;
    }
    if (newPwd !== confirmPwd) {
      Utils.toast('两次输入的新密码不一致', 'error');
      return;
    }
    if (newPwd.length < 8 || newPwd.length > 20) {
      Utils.toast('密码长度需为8-20位', 'error');
      return;
    }

    const success = await API.user.changePassword(oldPwd, newPwd);
    if (success) {
      Utils.toast('密码修改成功，请重新登录', 'success');
      setTimeout(() => {
        WS.disconnect();
        Utils.storage.clear();
        window.location.href = '/index.html';
      }, 1500);
    }
  },

  async logoutDevice(deviceId) {
    await API.user.logoutDevice(deviceId);
    Utils.toast('设备已下线', 'success');
    this.showDevices();
  },

  /**
   * 渲染侧边栏头部小头像
   */
  renderSidebarUserAvatar() {
    const name = this.currentUser.nickname || this.currentUser.username;
    const container = document.getElementById('sidebarUserAvatar');
    if (container) {
      container.innerHTML = Utils.avatarHTML(name, this.currentUser.avatar, 'sm', true);
    }
  },

  /**
   * 快速退出登录（从侧边栏头部按钮触发）
   */
  async quickLogout() {
    if (!confirm('确定退出登录吗？')) return;
    WS.disconnect();
    Utils.storage.clear();
    window.location.href = '/index.html';
  },

  async logout() {
    if (!confirm('确定退出登录吗？')) return;
    WS.disconnect();
    await API.auth.logout();
    API.clearAuth();
    window.location.href = '/index.html';
  },

  // ==================== 工具方法 ====================

  renderNavAvatar() {
    const name = this.currentUser.nickname || this.currentUser.username;
    document.getElementById('navAvatar').innerHTML = Utils.avatarHTML(name, this.currentUser.avatar, 'sm', true).replace('avatar avatar-sm', 'avatar avatar-sm');
  },

  handleSidebarSearch() {
    this.renderSidebar();
  },

  closeModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) modal.remove();
  },

  updateConversationPreview(msg) {
    // 更新会话列表中的预览消息
    // 后续可优化为只更新对应项
    this.renderSidebar();
  },

  /**
   * 下载文件
   */
  downloadFile(url, fileName) {
    if (!url) {
      Utils.toast('文件地址无效', 'error');
      return;
    }
    // 创建隐藏的下载链接，指定原始文件名
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName || '下载';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  },

  detectMobile() {
    const checkMobile = () => {
      const isMobile = window.innerWidth <= 768;
      document.querySelector('.mobile-nav').style.display = isMobile ? 'flex' : 'none';
      if (!isMobile) {
        document.getElementById('sidebar').classList.remove('hidden-mobile');
        document.querySelector('.mobile-back').style.display = 'none';
      }
    };
    checkMobile();
    window.addEventListener('resize', checkMobile);
  },

  mobileBack() {
    document.getElementById('sidebar').classList.remove('hidden-mobile');
    document.getElementById('chatActive').style.display = 'none';
    document.getElementById('chatEmpty').classList.remove('hidden');
  }
};

// 初始化
document.addEventListener('DOMContentLoaded', () => {
  if (typeof AdminModule !== 'undefined') {
    AdminModule.init();
  }
  App.init();
});
