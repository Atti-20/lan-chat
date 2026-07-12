/**
 * LanChat — websocket.js
 * WebSocket 连接管理器
 */

const WS = {
  socket: null,
  userId: null,
  reconnectAttempts: 0,
  maxReconnectAttempts: 5,
  reconnectDelay: 3000,
  heartbeatTimer: null,
  isManualClose: false,

  // 事件回调
  handlers: {
    onOpen: null,
    onClose: null,
    onChat: null,
    onRecall: null,
    onBurn: null,
    onRead: null,
    onTyping: null,
    onScreenshot: null,
    onSystem: null,
    onOnlineList: null,
    onError: null
  },

  /**
   * 连接 WebSocket
   * @param {number} userId - 用户 ID
   */
  connect(userId) {
    this.userId = userId;
    this.isManualClose = false;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const url = `${protocol}//${host}/ws/chat?userId=${userId}`;

    try {
      this.socket = new WebSocket(url);
      this.bindEvents();
    } catch (err) {
      console.error('WebSocket connection failed:', err);
      this.scheduleReconnect();
    }
  },

  /**
   * 绑定 WebSocket 事件
   */
  bindEvents() {
    this.socket.onopen = (event) => {
      console.log('WebSocket connected');
      this.reconnectAttempts = 0;
      this.startHeartbeat();
      if (this.handlers.onOpen) this.handlers.onOpen(event);
    };

    this.socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        this.handleMessage(msg);
      } catch (err) {
        console.error('Failed to parse WebSocket message:', err);
      }
    };

    this.socket.onclose = (event) => {
      console.log('WebSocket closed:', event.code, event.reason);
      this.stopHeartbeat();
      if (this.handlers.onClose) this.handlers.onClose(event);

      if (!this.isManualClose) {
        this.scheduleReconnect();
      }
    };

    this.socket.onerror = (event) => {
      console.error('WebSocket error:', event);
      if (this.handlers.onError) this.handlers.onError(event);
    };
  },

  /**
   * 处理收到的消息
   */
  handleMessage(msg) {
    const handlerMap = {
      'chat': 'onChat',
      'recall': 'onRecall',
      'burn': 'onBurn',
      'read': 'onRead',
      'typing': 'onTyping',
      'screenshot': 'onScreenshot',
      'system': 'onSystem',
      'online-list': 'onOnlineList',
      'error': 'onError'
    };

    const handlerName = handlerMap[msg.type];
    if (handlerName && this.handlers[handlerName]) {
      this.handlers[handlerName](msg);
    }
  },

  /**
   * 发送消息
   * @param {Object} data - 消息数据
   * @returns {boolean} 是否发送成功
   */
  send(data) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(data));
      return true;
    }
    Utils.toast('连接已断开，正在重连...', 'warn');
    return false;
  },

  /**
   * 发送聊天消息
   */
  sendChat(msg) {
    return this.send({
      type: 'chat',
      messageId: msg.messageId || Utils.uuid(),
      toUserId: msg.toUserId || null,
      groupId: msg.groupId || null,
      contentType: msg.contentType || 'text',
      content: msg.content || '',
      replyToId: msg.replyToId || null,
      mentionUserIds: msg.mentionUserIds || null,
      isBurn: msg.isBurn || false,
      burnDuration: msg.burnDuration || 5
    });
  },

  /**
   * 发送撤回消息
   */
  sendRecall(messageId) {
    return this.send({
      type: 'recall',
      messageId
    });
  },

  /**
   * 发送阅后即焚
   */
  sendBurn(messageId) {
    return this.send({
      type: 'burn',
      messageId
    });
  },

  /**
   * 发送已读回执
   */
  sendRead(toUserId) {
    return this.send({
      type: 'read',
      toUserId
    });
  },

  /**
   * 发送正在输入
   */
  sendTyping(toUserId, groupId) {
    return this.send({
      type: 'typing',
      toUserId: toUserId || null,
      groupId: groupId || null
    });
  },

  /**
   * 发送截屏通知
   */
  sendScreenshot(messageId) {
    return this.send({
      type: 'screenshot',
      messageId
    });
  },

  /**
   * 开始心跳
   */
  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) {
        // 发送一个 ping（利用 system 类型空消息）
        this.socket.send(JSON.stringify({ type: 'ping' }));
      }
    }, 30000);
  },

  /**
   * 停止心跳
   */
  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  },

  /**
   * 安排重连
   */
  scheduleReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      Utils.toast('连接断开，请刷新页面重试', 'error', 5000);
      return;
    }

    this.reconnectAttempts++;
    const delay = this.reconnectDelay * this.reconnectAttempts;
    console.log(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);

    setTimeout(() => {
      if (!this.isManualClose) {
        this.connect(this.userId);
      }
    }, delay);
  },

  /**
   * 主动关闭连接
   */
  disconnect() {
    this.isManualClose = true;
    this.stopHeartbeat();
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
  },

  /**
   * 设置事件处理器
   */
  on(event, callback) {
    const eventMap = {
      open: 'onOpen',
      close: 'onClose',
      chat: 'onChat',
      recall: 'onRecall',
      burn: 'onBurn',
      read: 'onRead',
      typing: 'onTyping',
      screenshot: 'onScreenshot',
      system: 'onSystem',
      onlineList: 'onOnlineList',
      error: 'onError'
    };
    const key = eventMap[event];
    if (key) {
      this.handlers[key] = callback;
    }
  }
};
