package com.lanchat.controller;

import com.lanchat.common.Result;
import com.lanchat.dto.*;
import com.lanchat.entity.Broadcast;
import com.lanchat.entity.BroadcastReceiver;
import com.lanchat.security.LoginUser;
import com.lanchat.security.UserContextHolder;
import com.lanchat.service.BroadcastService;
import com.lanchat.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/v1/broadcast")
public class BroadcastController {

    private final BroadcastService broadcastService;

    @Autowired(required = false)
    private ChatWebSocketHandler webSocketHandler;

    public BroadcastController(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @PostMapping
    public Result<Broadcast> create(@RequestBody BroadcastCreateDTO request) {
        LoginUser user = currentUser();
        Broadcast created = broadcastService.create(user.getUserId(), request);
        if (webSocketHandler != null) webSocketHandler.publishBroadcast(created.getId());
        return Result.success(created);
    }

    /** Broadcasts created by or addressed to the current user; administrators see all. */
    @GetMapping
    public Result<List<Broadcast>> list() {
        LoginUser user = currentUser();
        return Result.success(broadcastService.listVisible(user.getUserId()));
    }

    /** Active offline work that has not been viewed or still needs confirmation. */
    @GetMapping("/pending")
    public Result<List<Broadcast>> pending() {
        LoginUser user = currentUser();
        return Result.success(broadcastService.listPending(user.getUserId()));
    }

    @GetMapping("/{broadcastId}")
    public Result<BroadcastDetailDTO> detail(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        return Result.success(broadcastService.getDetail(broadcastId, user.getUserId()));
    }

    @PostMapping("/{broadcastId}/view")
    public Result<BroadcastReceiver> view(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        BroadcastReceiver viewed = broadcastService.markViewed(broadcastId, user.getUserId());
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastUpdated(broadcastId, user.getUserId());
        }
        return Result.success(viewed);
    }

    @PostMapping("/{broadcastId}/confirm")
    public Result<BroadcastReceiver> confirm(@PathVariable Long broadcastId,
                                             @RequestBody BroadcastConfirmDTO request) {
        LoginUser user = currentUser();
        BroadcastReceiver confirmed = broadcastService.confirm(
                broadcastId,
                user.getUserId(),
                user.getDeviceType(),
                request
        );
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastUpdated(broadcastId, user.getUserId());
        }
        return Result.success(confirmed);
    }

    @GetMapping("/{broadcastId}/stats")
    public Result<BroadcastStatsDTO> stats(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        return Result.success(broadcastService.getStats(broadcastId, user.getUserId()));
    }

    @PostMapping("/{broadcastId}/cancel")
    public Result<Broadcast> cancel(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        Broadcast cancelled = broadcastService.cancel(broadcastId, user.getUserId());
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastCancelled(cancelled);
        }
        return Result.success(cancelled);
    }

    @DeleteMapping("/{broadcastId}")
    public Result<Void> delete(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        BroadcastDeleteResult deleted = broadcastService.delete(broadcastId, user.getUserId());
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastDeleted(deleted.broadcast(), deleted.receiverIds());
        }
        return Result.success();
    }

    @PostMapping("/{broadcastId}/complete")
    public Result<BroadcastReceiver> complete(@PathVariable Long broadcastId, @RequestBody(required = false) BroadcastCompleteDTO request) {
        LoginUser user = currentUser();
        BroadcastReceiver completed = broadcastService.complete(
                broadcastId,
                user.getUserId(),
                user.getDeviceType(),
                request
        );
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastUpdated(broadcastId, user.getUserId());
        }
        return Result.success(completed);
    }

    @GetMapping("/{broadcastId}/receivers")
    public Result<List<BroadcastRecipientDetailDTO>>
    receivers(@PathVariable Long broadcastId, @RequestParam(defaultValue = "ALL") String bucket) {
        LoginUser user = currentUser();
        return Result.success(broadcastService.listRecipients(broadcastId, user.getUserId(),  bucket));
    }

    @GetMapping("/{broadcastId}/export.xlsx")
    public ResponseEntity<byte[]> exportExcel(@PathVariable Long broadcastId) {
        LoginUser user = currentUser();
        List<BroadcastRecipientDetailDTO> recipients = broadcastService.listRecipients(
                broadcastId, user.getUserId(), "ALL");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("接收者明细");
            String[] headings = {"用户名", "昵称", "目标状态", "送达时间", "查看时间", "执行状态", "完成时间", "图片数量", "纬度", "经度", "定位精度", "提醒次数", "最后提醒时间"};
            var header = sheet.createRow(0);
            for (int index = 0; index < headings.length; index++) header.createCell(index).setCellValue(headings[index]);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (int rowIndex = 0; rowIndex < recipients.size(); rowIndex++) {
                BroadcastRecipientDetailDTO item = recipients.get(rowIndex);
                var row = sheet.createRow(rowIndex + 1);
                row.createCell(0).setCellValue(item.username());
                row.createCell(1).setCellValue(item.nickname());
                row.createCell(2).setCellValue(item.targetStatus());
                row.createCell(3).setCellValue(formatDate(item.deliveredAt(), formatter));
                row.createCell(4).setCellValue(formatDate(item.viewedAt(), formatter));
                row.createCell(5).setCellValue(item.confirmStatus());
                row.createCell(6).setCellValue(formatDate(item.completedAt(), formatter));
                row.createCell(7).setCellValue(item.imageUrls().size());
                row.createCell(8).setCellValue(item.location() == null || item.location().getLatitude() == null ? "" : item.location().getLatitude().toPlainString());
                row.createCell(9).setCellValue(item.location() == null || item.location().getLongitude() == null ? "" : item.location().getLongitude().toPlainString());
                row.createCell(10).setCellValue(item.location() == null || item.location().getAccuracyMeters() == null ? "" : item.location().getAccuracyMeters().toPlainString());
                row.createCell(11).setCellValue(item.remindCount());
                row.createCell(12).setCellValue(formatDate(item.lastRemindedAt(), formatter));
            }
            for (int column = 0; column < headings.length; column++) sheet.autoSizeColumn(column);
            workbook.write(output);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("broadcast-" + broadcastId + ".xlsx", StandardCharsets.UTF_8).build().toString())
                    .body(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("广播导出失败", exception);
        }
    }

    private String formatDate(java.time.LocalDateTime value, DateTimeFormatter formatter) {
        return value == null ? "" : formatter.format(value);
    }

    @PostMapping("/{broadcastId}/receivers/{receiverUserId}/remind")
    public Result<Void> remind(@PathVariable Long broadcastId, @PathVariable Long receiverUserId) {
        LoginUser user = currentUser();
        broadcastService.remindReceiver(broadcastId, receiverUserId, user.getUserId());
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastReminder(broadcastId, receiverUserId, user.getUserId());
        }
        return Result.success();
    }

    @PatchMapping("/{broadcastId}/receivers")
    public Result<BroadcastTargetUpdateResultDTO> updateTargets(@PathVariable Long broadcastId, @RequestBody BroadcastTargetUpdateDTO request) {
        LoginUser user = currentUser();
        BroadcastTargetUpdateResultDTO result = broadcastService.updateTargets(broadcastId, user.getUserId(), request);
        if (webSocketHandler != null) {
            webSocketHandler.notifyBroadcastTargetsUpdated(
                    broadcastId, result.addedUserIds(), result.removedUserIds());
        }
        return Result.success(result);
    }

    private LoginUser currentUser() {
        LoginUser user = UserContextHolder.getCurrentUser();
        if (user == null) throw new AccessDeniedException("请先登录");
        return user;
    }
}
