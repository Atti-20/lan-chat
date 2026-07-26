<script setup lang="ts">
import type {
  AdminUser,
  BroadcastCreatePayload,
  EmergencyBroadcast,
  Friend,
  TemporaryRoomCreatePayload,
  User,
} from '../../types'
import AdminPasswordResetModal from '../admin/AdminPasswordResetModal.vue'
import CreateBroadcastModal from '../broadcasts/CreateBroadcastModal.vue'
import EmergencyBroadcastAlert from '../broadcasts/EmergencyBroadcastAlert.vue'
import DesktopSettingsModal from '../desktop/DesktopSettingsModal.vue'
import CreateTemporaryRoomModal from '../rooms/CreateTemporaryRoomModal.vue'
import JoinTemporaryRoomModal from '../rooms/JoinTemporaryRoomModal.vue'
import ChangePasswordModal from './ChangePasswordModal.vue'
import CreateGroupModal from './CreateGroupModal.vue'
import DeviceManagerModal from './DeviceManagerModal.vue'
import FileTransferSettingsModal from './FileTransferSettingsModal.vue'
import PersonalProfileModal from './PersonalProfileModal.vue'
import ProfileModal from './ProfileModal.vue'
import SearchPeopleModal from './SearchPeopleModal.vue'

interface Props {
  searchOpen?: boolean
  groupOpen?: boolean
  roomCreateOpen?: boolean
  roomJoinOpen?: boolean
  broadcastCreateOpen?: boolean
  profileOpen?: boolean
  profileEditorOpen?: boolean
  fileTransferSettingsOpen?: boolean
  desktopSettingsOpen?: boolean
  devicesOpen?: boolean
  passwordOpen?: boolean
  passwordResetTarget?: AdminUser | null
  user: User
  friendIds: readonly number[]
  friends: readonly Friend[]
  groupSaving?: boolean
  roomSaving?: boolean
  administrator?: boolean
  broadcastSaving?: boolean
  emergencyBroadcast?: EmergencyBroadcast | null
  emergencyConfirmationOptions?: readonly string[]
  broadcastConfirming?: boolean
  profileSaving?: boolean
  adminBusyUserId?: number | null
  connectionSummary?: string
  desktop?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  searchOpen: false,
  groupOpen: false,
  roomCreateOpen: false,
  roomJoinOpen: false,
  broadcastCreateOpen: false,
  profileOpen: false,
  profileEditorOpen: false,
  fileTransferSettingsOpen: false,
  desktopSettingsOpen: false,
  devicesOpen: false,
  passwordOpen: false,
  passwordResetTarget: null,
  groupSaving: false,
  roomSaving: false,
  administrator: false,
  broadcastSaving: false,
  emergencyBroadcast: null,
  emergencyConfirmationOptions: () => [],
  broadcastConfirming: false,
  profileSaving: false,
  adminBusyUserId: null,
  connectionSummary: '',
  desktop: false,
})

const emit = defineEmits<{
  closeSearch: []
  friendRequest: [userId: number, message: string]
  closeGroup: []
  createGroup: [name: string, memberIds: number[]]
  closeRoomCreate: []
  createRoom: [payload: TemporaryRoomCreatePayload]
  closeRoomJoin: []
  joinRoom: [roomCode: string]
  closeBroadcastCreate: []
  createBroadcast: [payload: BroadcastCreatePayload]
  openBroadcast: [broadcastId: number]
  confirmBroadcast: [broadcastId: number, status: string]
  dismissEmergency: [broadcastId: number]
  closeProfile: []
  openProfileEditor: []
  openDevices: []
  openPassword: []
  openFileTransferSettings: []
  openDesktopSettings: []
  logout: []
  closeProfileEditor: []
  saveProfile: [payload: { nickname: string; avatar: string }]
  closeFileTransferSettings: []
  closeDesktopSettings: []
  switchNode: []
  closeDevices: []
  currentDeviceLoggedOut: []
  closePassword: []
  passwordChanged: []
  closePasswordReset: []
  resetPassword: [newPassword: string]
}>()
</script>

<template>
  <SearchPeopleModal
    :open="searchOpen"
    :current-user-id="user.id"
    :friend-ids="friendIds"
    @close="emit('closeSearch')"
    @request="(userId, message) => emit('friendRequest', userId, message)"
  />
  <CreateGroupModal
    :open="groupOpen"
    :friends="friends"
    :saving="groupSaving"
    @close="emit('closeGroup')"
    @create="(name, memberIds) => emit('createGroup', name, memberIds)"
  />
  <CreateTemporaryRoomModal
    :open="roomCreateOpen"
    :saving="roomSaving"
    @close="emit('closeRoomCreate')"
    @create="emit('createRoom', $event)"
  />
  <JoinTemporaryRoomModal
    :open="roomJoinOpen"
    :saving="roomSaving"
    @close="emit('closeRoomJoin')"
    @join="emit('joinRoom', $event)"
  />
  <CreateBroadcastModal
    :open="broadcastCreateOpen"
    :friends="friends"
    :is-admin="administrator"
    :saving="broadcastSaving"
    @close="emit('closeBroadcastCreate')"
    @create="emit('createBroadcast', $event)"
  />
  <EmergencyBroadcastAlert
    :broadcast="emergencyBroadcast"
    :confirmation-options="emergencyConfirmationOptions"
    :busy="broadcastConfirming"
    @open="emit('openBroadcast', $event)"
    @confirm="(broadcastId, status) => emit('confirmBroadcast', broadcastId, status)"
    @dismiss="emit('dismissEmergency', $event)"
  />
  <PersonalProfileModal
    :open="profileOpen"
    :user="user"
    :desktop="desktop"
    :connection-summary="connectionSummary"
    @close="emit('closeProfile')"
    @open-profile-editor="emit('openProfileEditor')"
    @open-devices="emit('openDevices')"
    @open-password="emit('openPassword')"
    @open-file-transfer-settings="emit('openFileTransferSettings')"
    @open-desktop-settings="emit('openDesktopSettings')"
    @logout="emit('logout')"
  />
  <ProfileModal
    :open="profileEditorOpen"
    :user="user"
    :saving="profileSaving"
    @close="emit('closeProfileEditor')"
    @save="emit('saveProfile', $event)"
  />
  <FileTransferSettingsModal
    :open="fileTransferSettingsOpen"
    @close="emit('closeFileTransferSettings')"
  />
  <DesktopSettingsModal
    :open="desktopSettingsOpen"
    @close="emit('closeDesktopSettings')"
    @switch-node="emit('switchNode')"
  />
  <DeviceManagerModal
    :open="devicesOpen"
    @close="emit('closeDevices')"
    @current-device-logged-out="emit('currentDeviceLoggedOut')"
  />
  <ChangePasswordModal
    :open="passwordOpen"
    @close="emit('closePassword')"
    @password-changed="emit('passwordChanged')"
  />
  <AdminPasswordResetModal
    :user="passwordResetTarget"
    :saving="Boolean(passwordResetTarget && adminBusyUserId === passwordResetTarget.id)"
    @close="emit('closePasswordReset')"
    @reset="emit('resetPassword', $event)"
  />
</template>
