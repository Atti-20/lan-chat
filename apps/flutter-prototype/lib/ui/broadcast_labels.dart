// Product vocabulary shared with Web BroadcastWorkspace; wire values stay intact.
String broadcastPriorityLabel(String value) =>
    const {'NORMAL': '普通通知', 'IMPORTANT': '重要通知', 'EMERGENCY': '紧急广播'}[value] ??
    value;

String broadcastConfirmationLabel(String? value) =>
    const {
      'PENDING': '等待确认',
      'NOT_REQUIRED': '无需确认',
      'DELIVERED': '已送达',
      'VIEWED': '已查看',
      'RECEIVED': '已收到',
      'EXECUTED': '已执行',
      'NEED_SUPPORT': '需要支援',
      'EXPIRED': '已过期',
    }[value] ??
    (value == null || value.isEmpty ? '未送达' : value);

String broadcastStatusLabel(String value) =>
    const {'ACTIVE': '进行中', 'COMPLETED': '已完成', 'CANCELLED': '已取消'}[value] ??
    value;

String broadcastTargetLabel(String? value) =>
    const {'ACTIVE': '接收范围内', 'REMOVED': '已移出接收范围'}[value] ??
    (value ?? '无接收记录');
