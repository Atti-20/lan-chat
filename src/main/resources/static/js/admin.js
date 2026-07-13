/**
 * LanChat — admin.js
 * 管理员控制台前端模块
 */

const AdminModule = {
  init() {
    // 管理控制台按钮已移至"我的"面板操作列表中
    // 通过 App.openAdminConsole() 触发
  },

  async loadUserList() {
    const tbody = document.getElementById('admin-user-table-body');
    const totalBadge = document.getElementById('admin-total-users');

    try {
      const res = await API.request('GET', '/admin/users');

      if (!res) {
        tbody.innerHTML = `<tr><td colspan="6" class="admin-table-error">加载失败：数据为空</td></tr>`;
        return;
      }

      let users = res;
      if (res.data && Array.isArray(res.data)) {
        users = res.data;
      } else if (res.list && Array.isArray(res.list)) {
        users = res.list;
      } else if (res.content && Array.isArray(res.content)) {
        users = res.content;
      }

      if (!Array.isArray(users) || users.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" class="admin-table-loading">暂无用户数据</td></tr>`;
        totalBadge.innerText = '0 人';
        return;
      }

      totalBadge.innerText = `${users.length} 人`;
      tbody.innerHTML = '';

      users.forEach((user) => {
        const isBanned = user.status === 0;
        const statusBadge = isBanned
          ? `<span class="admin-status-badge admin-status-banned">已被封禁</span>`
          : `<span class="admin-status-badge admin-status-active">正常运行</span>`;

        const actionButton = isBanned
          ? `<button class="admin-action-btn success" onclick="AdminModule.toggleStatus(${user.id}, 1)">解除封禁</button>`
          : `<button class="admin-action-btn danger" onclick="AdminModule.toggleStatus(${user.id}, 0)">执行封禁</button>`;

        const muteInfo = (user.muteStart && user.muteEnd)
          ? `<span class="admin-text-bold">${user.muteStart} ~ ${user.muteEnd}</span>`
          : `<span class="admin-text-muted">未设置</span>`;

        const tr = document.createElement('tr');
        tr.innerHTML = `
          <td><code class="admin-mono">${user.id}</code></td>
          <td class="admin-text-bold">${user.username}</td>
          <td>${user.nickname || '-'}</td>
          <td>${statusBadge}</td>
          <td>${muteInfo}</td>
          <td>
            <div class="admin-action-group">
              ${user.username === 'admin' ? '<span class="admin-protected-label">系统根权限受保护</span>' : actionButton}
              ${user.username === 'admin' ? '' : `
                <button class="admin-action-btn" onclick="AdminModule.showMutePrompt(${user.id})">时段禁言</button>
                <button class="admin-action-btn danger" onclick="AdminModule.deleteUser(${user.id})">删除</button>
              `}
            </div>
          </td>
        `;
        tbody.appendChild(tr);
      });

    } catch (error) {
      tbody.innerHTML = `<tr><td colspan="6" class="admin-table-error">网络通讯异常：${error.message || '未知错误'}</td></tr>`;
    }
  },

  async toggleStatus(userId, targetStatus) {
    const actionText = targetStatus === 0 ? '确认要封禁该用户吗？封禁后该账号将无法登录系统。' : '确认要解封该用户吗？';
    if (!confirm(actionText)) return;

    const formData = new FormData();
    formData.append('userId', userId);
    formData.append('status', targetStatus);

    const success = await API.request('POST', '/admin/user/status', formData, true);
    if (success) {
      Utils.toast(targetStatus === 0 ? '封禁指令已即时生效' : '账号已解封', 'success');
      this.loadUserList();
    }
  },

  async showMutePrompt(userId) {
    const timeRange = prompt('请输入禁言/免打扰时间段，格式如 22:00-08:00：', '22:00-08:00');
    if (!timeRange) return;

    const times = timeRange.split('-');
    if (times.length !== 2) {
      Utils.toast('输入格式有误，必须用横线隔开，例如 22:00-08:00', 'error');
      return;
    }

    const formData = new FormData();
    formData.append('userId', userId);
    formData.append('muteStart', times[0].trim());
    formData.append('muteEnd', times[1].trim());

    const success = await API.request('POST', '/admin/user/mute', formData, true);
    if (success) {
      Utils.toast('全局时段设置成功', 'success');
      this.loadUserList();
    }
  },

  async deleteUser(userId) {
    if (!confirm('确定要永久删除该用户吗？该操作不可撤销，该用户的所有数据将被一并清除。')) return;
    if (!confirm('再次确认：删除后数据无法恢复，是否继续？')) return;

    const success = await API.admin.deleteUser(userId);
    if (success) {
      Utils.toast('用户已永久删除', 'success');
      this.loadUserList();
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  AdminModule.init();
});
