/**
 * LanChat — api.js
 * 后端 API 统一封装
 */

const API = {
  baseUrl: '/api/v1',

  /**
   * 获取请求头
   */
  getHeaders() {
    const token = Utils.storage.get('token');
    return {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': 'Bearer ' + token } : {})
    };
  },

  /**
   * 统一请求方法
   */
  async request(method, path, data, isFormData = false) {
    const url = this.baseUrl + path;
    const options = {
      method,
      headers: isFormData ? {} : this.getHeaders()
    };

    if (isFormData) {
      const token = Utils.storage.get('token');
      if (token) options.headers['Authorization'] = 'Bearer ' + token;
      options.body = data;
    } else if (data !== undefined && data !== null) {
      options.body = JSON.stringify(data);
    }

    try {
      const response = await fetch(url, options);

      // 401 尝试刷新 token
      if (response.status === 401) {
        const refreshed = await this.refreshToken();
        if (refreshed) {
          options.headers = isFormData ? { 'Authorization': 'Bearer ' + Utils.storage.get('token') } : this.getHeaders();
          if (isFormData) {
            options.body = data;
          } else if (data !== undefined && data !== null) {
            options.body = JSON.stringify(data);
          }
          const retryResponse = await fetch(url, options);
          return this.handleResponse(retryResponse);
        }
        // 刷新失败，跳转登录
        this.clearAuth();
        window.location.href = '/index.html';
        return null;
      }

      return this.handleResponse(response);
    } catch (err) {
      console.error('API request error:', err);
      Utils.toast('网络请求失败，请检查连接', 'error');
      return null;
    }
  },

  /**
   * 处理响应
   */
  async handleResponse(response) {
    if (response.status === 401) {
      this.clearAuth();
      window.location.href = '/index.html';
      return null;
    }

    // 文件预览等直接返回 blob
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.indexOf('application/json') === -1) {
      return response;
    }

    const result = await response.json();
    if (result.code === 200) {
      return result.data;
    } else {
      Utils.toast(result.msg || '操作失败', 'error');
      return null;
    }
  },

  /**
   * 刷新 token
   */
  async refreshToken() {
    const refreshToken = Utils.storage.get('refreshToken');
    if (!refreshToken) return false;

    try {
      const response = await fetch(this.baseUrl + '/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken })
      });
      const result = await response.json();
      if (result.code === 200 && result.data) {
        Utils.storage.set('token', result.data.token);
        Utils.storage.set('refreshToken', result.data.refreshToken);
        Utils.storage.set('expiresIn', result.data.expiresIn);
        return true;
      }
    } catch (err) {
      console.error('Token refresh failed:', err);
    }
    return false;
  },

  /**
   * 清除认证信息
   */
  clearAuth() {
    Utils.storage.remove('token');
    Utils.storage.remove('refreshToken');
    Utils.storage.remove('userId');
    Utils.storage.remove('userInfo');
    Utils.storage.remove('expiresIn');
  },

  // ==================== 认证接口 ====================

  auth: {
    register(username, password, nickname) {
      return API.request('POST', '/auth/register', { username, password, nickname });
    },

    login(username, password, deviceType = 'web', deviceName = navigator.userAgent.substring(0, 50)) {
      return API.request('POST', '/auth/login', { username, password, deviceType, deviceName });
    },

    logout() {
      return API.request('POST', '/auth/logout');
    }
  },

  // ==================== 用户接口 ====================

  user: {
    getInfo() {
      return API.request('GET', '/user/info');
    },

    getById(id) {
      return API.request('GET', `/user/${id}`);
    },

    getOnline() {
      return API.request('GET', '/user/online');
    },

    search(keyword) {
      return API.request('GET', `/user/search?keyword=${encodeURIComponent(keyword)}`);
    },

    getDevices() {
      return API.request('GET', '/user/devices');
    },

    logoutDevice(deviceId) {
      return API.request('DELETE', `/user/devices/${deviceId}`);
    },

    setMutePeriod(muteStart, muteEnd) {
      return API.request('PUT', `/user/mute-period?muteStart=${muteStart}&muteEnd=${muteEnd}`);
    },

    getMuteStatus() {
      return API.request('GET', '/user/mute-status');
    }
  },

  // ==================== 好友接口 ====================

  friend: {
    sendRequest(toUserId, message) {
      return API.request('POST', '/friend/request', { toUserId, message });
    },

    getRequests() {
      return API.request('GET', '/friend/requests');
    },

    handleRequest(requestId, accept) {
      return API.request('POST', '/friend/handle', { requestId, accept });
    },

    getList() {
      return API.request('GET', '/friend/list');
    },

    delete(friendId) {
      return API.request('DELETE', `/friend/${friendId}`);
    },

    toggleBlock(friendId) {
      return API.request('PUT', `/friend/${friendId}/block`);
    },

    setRemark(friendId, remark) {
      return API.request('PUT', `/friend/${friendId}/remark?remark=${encodeURIComponent(remark)}`);
    },

    setGroup(friendId, groupName) {
      return API.request('PUT', `/friend/${friendId}/group?groupName=${encodeURIComponent(groupName)}`);
    },

    togglePin(friendId) {
      return API.request('PUT', `/friend/${friendId}/pin`);
    },

    toggleMute(friendId) {
      return API.request('PUT', `/friend/${friendId}/mute`);
    }
  },

  // ==================== 群组接口 ====================

  group: {
    create(groupName, memberIds) {
      return API.request('POST', '/group', { groupName, memberIds });
    },

    getById(groupId) {
      return API.request('GET', `/group/${groupId}`);
    },

    update(groupId, data) {
      return API.request('PUT', `/group/${groupId}`, data);
    },

    getMy() {
      return API.request('GET', '/group/my');
    },

    getMembers(groupId) {
      return API.request('GET', `/group/${groupId}/members`);
    },

    addMembers(groupId, userIds) {
      return API.request('POST', `/group/${groupId}/members`, userIds);
    },

    removeMember(groupId, memberId) {
      return API.request('DELETE', `/group/${groupId}/members/${memberId}`);
    },

    leave(groupId) {
      return API.request('POST', `/group/${groupId}/leave`);
    },

    transfer(groupId, newOwnerId) {
      return API.request('PUT', `/group/${groupId}/transfer?newOwnerId=${newOwnerId}`);
    },

    setAdmin(groupId, userId, isAdmin) {
      return API.request('PUT', `/group/${groupId}/admin?userId=${userId}&isAdmin=${isAdmin}`);
    },

    muteMember(groupId, userId, muteMinutes) {
      return API.request('PUT', `/group/${groupId}/mute`, { userId, muteMinutes });
    },

    dissolve(groupId) {
      return API.request('DELETE', `/group/${groupId}/dissolve`);
    }
  },

  // ==================== 消息接口 ====================

  chat: {
    getGroupHistory(groupId, limit = 50) {
      return API.request('GET', `/chat/history/group?groupId=${groupId}&limit=${limit}`);
    },

    getPrivateHistory(userId, targetId, limit = 50) {
      return API.request('GET', `/chat/history/private?userId=${userId}&targetId=${targetId}&limit=${limit}`);
    },

    markAsRead(fromUserId, toUserId) {
      return API.request('PUT', `/chat/read?fromUserId=${fromUserId}&toUserId=${toUserId}`);
    },

    recall(messageId) {
      return API.request('POST', `/chat/recall?messageId=${messageId}`);
    },

    burn(messageId) {
      return API.request('POST', `/chat/burn?messageId=${messageId}`);
    },

    search(keyword, limit = 50) {
      return API.request('GET', `/chat/search?keyword=${encodeURIComponent(keyword)}&limit=${limit}`);
    }
  },

  // ==================== 文件接口 ====================

  file: {
    check(fileHash) {
      return API.request('POST', '/file/check', { fileHash });
    },

    upload(formData) {
      return API.request('POST', '/file/upload', formData, true);
    },

    generatePreviewUrl(fileName) {
      return API.request('POST', `/file/preview-url?fileName=${encodeURIComponent(fileName)}`);
    }
  }
};
