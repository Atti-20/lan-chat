/**
 * 局域网聊天室 - 超级管理员控制前端核心模块
 */
const AdminModule = {
    // 初始化入口
    init() {
        // 1. 从 localStorage 中读取当前登录的用户信息
        const currentUserStr = localStorage.getItem('lanchat_userInfo');
        console.log('读取到的用户信息:', currentUserStr);
        if (!currentUserStr) return;
        try {
            const currentUser = JSON.parse(currentUserStr);
            // 2. 核心权限判断：如果用户名为 admin，则激活后台入口
            if (currentUser && currentUser.username === 'admin') {
                const adminBtn = document.getElementById('admin-console-btn');
                if (adminBtn) {
                    adminBtn.style.display = 'block'; // 显示管理按钮
                    // 绑定点击事件，打开弹窗时自动触发数据拉取
                    adminBtn.addEventListener('click', () => {
                        document.getElementById('adminConsoleModal').style.display = 'block';
                        this.loadUserList();
                    });
                }
            }
        } catch (e) {
            console.error('解析用户信息失败：', e)
        }
    },

    // 异步拉取全站用户列表并渲染
    async loadUserList() {
        const tbody = document.getElementById('admin-user-table-body');
        const totalBadge = document.getElementById('admin-total-users');

        try {
            console.log('📌 开始加载用户列表...');

            // 使用 API.request 直接调用
            const res = await API.request('GET', '/admin/users');

            console.log('📌 API 返回结果:', res);
            console.log('📌 数据类型:', typeof res);
            console.log('📌 是否为数组:', Array.isArray(res));
            console.log('📌 数据长度:', res ? res.length : 0);

            // 检查返回数据
            if (!res) {
                console.warn('⚠️ 返回数据为空');
                tbody.innerHTML = `<tr><td colspan="6" class="text-danger fw-bold py-3">加载失败：数据为空</td></tr>`;
                return;
            }

            // 如果返回的是对象，尝试提取数据
            let users = res;
            if (res.data && Array.isArray(res.data)) {
                users = res.data;
            } else if (res.list && Array.isArray(res.list)) {
                users = res.list;
            } else if (res.content && Array.isArray(res.content)) {
                users = res.content;
            }

            if (!Array.isArray(users) || users.length === 0) {
                console.warn('⚠️ 用户列表为空或不是数组');
                tbody.innerHTML = `<tr><td colspan="6" class="text-muted py-3 text-center">暂无用户数据</td></tr>`;
                totalBadge.innerText = '0 人';
                return;
            }

            console.log(`✅ 成功获取 ${users.length} 个用户`);
            totalBadge.innerText = `${users.length} 人`;

            // 清空并渲染
            tbody.innerHTML = '';

            users.forEach((user, index) => {
                console.log(`📌 渲染用户 ${index + 1}:`, user);

                const isBanned = user.status === 0;
                const statusBadge = isBanned
                    ? `<span class="badge bg-danger">已被封禁</span>`
                    : `<span class="badge bg-success">正常运行</span>`;

                const actionButton = isBanned
                    ? `<button class="btn btn-sm btn-outline-success me-2" onclick="AdminModule.toggleStatus(${user.id}, 1)">解除封禁</button>`
                    : `<button class="btn btn-sm btn-outline-danger me-2" onclick="AdminModule.toggleStatus(${user.id}, 0)">执行封禁</button>`;

                const muteInfo = (user.muteStart && user.muteEnd)
                    ? `<span class="text-dark fw-bold">${user.muteStart} ~ ${user.muteEnd}</span>`
                    : `<span class="text-muted">未设置</span>`;

                const tr = document.createElement('tr');
                tr.innerHTML = `
                <td><code>${user.id}</code></td>
                <td class="fw-bold">${user.username}</td>
                <td>${user.nickname || '-'}</td>
                <td>${statusBadge}</td>
                <td>${muteInfo}</td>
                <td>
                    <div class="d-inline-flex">
                        ${user.username === 'admin' ? '<span class="text-muted small">系统根权限受保护</span>' : actionButton}
                        ${user.username === 'admin' ? '' : `
                            <button class="btn btn-sm btn-outline-secondary" onclick="AdminModule.showMutePrompt(${user.id})">时段禁言</button>
                            <button class="btn btn-sm btn-outline-danger" onclick="AdminModule.deleteUser(${user.id})">删除</button>
                        `}
                    </div>
                </td>
            `;
                tbody.appendChild(tr);
            });

            console.log('✅ 用户列表渲染完成');

        } catch (error) {
            console.error("❌ 管理员列表获取异常:", error);
            console.error("错误详情:", error.stack);
            tbody.innerHTML = `<tr><td colspan="6" class="text-danger py-3">网络通讯异常：${error.message || '未知错误'}</td></tr>`;
        }
    },

    // 触发账号封禁切换控制
    async toggleStatus(userId, targetStatus) {
        const actionText = targetStatus === 0 ? "确认要【封禁】该用户吗？封禁后该账号将无法登录系统。" : "确认要【解封】该用户吗？";
        if (!confirm(actionText)) return;

        // 构建 FormData 参数形式，对应后端的 @RequestParam
        const formData = new FormData();
        formData.append('userId', userId);
        formData.append('status', targetStatus);

        const success = await API.request('POST', '/admin/user/status', formData, true);
        if (success) {
            Utils.toast(targetStatus === 0 ? "封禁指令已即时生效" : "账号已解封", "success");
            this.loadUserList(); // 自动刷新表格状态
        }
    },

    // 弹出输入框设置全局免打扰/禁言时段
    async showMutePrompt(userId) {
        const timeRange = prompt("请输入禁言/免打扰时间段，格式如 [22:00-08:00]：", "22:00-08:00");
        if (!timeRange) return;

        const times = timeRange.split('-');
        if (times.length !== 2) {
            Utils.toast("输入格式有误，必须用横线隔开，例如 22:00-08:00", "error");
            return;
        }

        const formData = new FormData();
        formData.append('userId', userId);
        formData.append('muteStart', times[0].trim());
        formData.append('muteEnd', times[1].trim());

        const success = await API.request('POST', '/admin/user/mute', formData, true);
        if (success) {
            Utils.toast("全局时段设置成功", "success");
            this.loadUserList();
        }
    },
    // 删除用户
    async deleteUser(userId) {
        if (!confirm("⚠️ 确定要永久删除该用户吗？\n\n该操作不可撤销，该用户的所有数据（好友、群组、消息、文件等）将被一并清除。")) return;
        if (!confirm("再次确认：删除后数据无法恢复，是否继续？")) return;

        const success = await API.admin.deleteUser(userId);
        if (success) {
            Utils.toast("用户已永久删除", "success");
            this.loadUserList();
        }
    }
};

// 在页面 DOM 加载完毕或者你的通用初始化逻辑里执行激活
document.addEventListener('DOMContentLoaded', () => {
    AdminModule.init();
});

console.log('admin.js已加载');