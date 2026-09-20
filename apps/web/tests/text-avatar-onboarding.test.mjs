import assert from 'node:assert/strict'
import { access, readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from '../node_modules/typescript/lib/typescript.js'

const source = (path) => readFile(new URL(path, import.meta.url), 'utf8')

test('registration avatar onboarding only offers a persisted text avatar or image upload', async () => {
  const welcome = await source('../src/views/WelcomeView.vue')

  assert.match(welcome, /createTextAvatar\(cleanName\)/)
  assert.match(welcome, /aria-label="使用昵称首字符作为头像"/)
  assert.match(welcome, /aria-label="上传自定义头像"/)
  assert.match(welcome, /background: linear-gradient\(145deg, #5856D6, #4644C4\)/)
  assert.doesNotMatch(welcome, /const avatars\s*=/)
  assert.doesNotMatch(welcome, /v-for="\(emoji/)
  assert.doesNotMatch(welcome, /选择头像 \$\{emoji\}/)
})

test('profile and onboarding share one text-avatar representation and default colour', async () => {
  const [profile, textAvatar] = await Promise.all([
    source('../src/components/chat/ProfileModal.vue'),
    source('../src/services/textAvatar.ts'),
  ])

  assert.match(profile, /createTextAvatar\(cleanNickname, color \|\| DEFAULT_TEXT_AVATAR_COLOR\)/)
  assert.match(profile, /TEXT_AVATAR_COLOR_PRESETS/)
  assert.match(profile, /repeat\(auto-fit, minmax\(44px, 1fr\)\)/)
  assert.match(textAvatar, /DEFAULT_TEXT_AVATAR_COLOR = '#5856D6'/)
  assert.match(textAvatar, /return `letter:\$\{textAvatarInitial\(name\)\}:\$\{color\}`/)
})

test('legacy preset animal avatar files are not shipped with the client', async () => {
  const legacyFiles = ['pebble', 'star', 'sprout', 'cloud', 'wave', 'moon', 'droplet', 'orbit']

  await Promise.all(legacyFiles.map((name) =>
    assert.rejects(access(new URL(`../public/avatars/${name}.jpg`, import.meta.url)), { code: 'ENOENT' })
  ))
})

test('onboarding nickname edits update the preview and save the same avatar without replacing uploads', async () => {
  const welcome = await source('../src/views/WelcomeView.vue')
  assert.match(welcome, /:avatar="previewAvatar"/)
  const script = welcome.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const parsed = ts.createSourceFile('WelcomeView.ts', script, ts.ScriptTarget.Latest, true)
  const body = parsed.statements.filter(node => !ts.isImportDeclaration(node))
    .map(node => node.getText(parsed)).join('\n')
  const avatar = await source('../src/services/textAvatar.ts')
  const vue = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
  const runtime = `
    import { computed, ref, shallowRef } from '${vue}';
    export const mounted = [], saved = [], navigations = [];
    const onMounted = callback => mounted.push(callback);
    const useAuth = () => ({
      currentUser: { value: { nickname: 'AvatarBefore', avatar: '' } },
      updateProfile: async profile => saved.push(profile),
    });
    const useToast = () => ({ push: () => {} });
    const navigateToApp = path => navigations.push(path);
    class ApiError extends Error {}
  `
  const compiled = ts.transpileModule(runtime + avatar + body + '\nexport { nickname, selectedAvatar, previewAvatar, chooseTextAvatar, finish };', {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const model = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
  await model.mounted[0]()
  assert.equal(model.previewAvatar.value, 'letter:A:#5856D6')
  model.nickname.value = '  头像复验0906  '
  assert.equal(model.previewAvatar.value, 'letter:头:#5856D6')
  await model.finish()
  assert.deepEqual(model.saved[0], { nickname: '头像复验0906', avatar: model.previewAvatar.value })
  model.selectedAvatar.value = '/uploads/avatar-test.png'
  model.nickname.value = '图片昵称'
  assert.equal(model.previewAvatar.value, '/uploads/avatar-test.png')
  await model.finish()
  assert.equal(model.saved[1].avatar, '/uploads/avatar-test.png')
  model.chooseTextAvatar()
  assert.equal(model.previewAvatar.value, 'letter:图:#5856D6')
  model.nickname.value = '最后昵称'
  assert.equal(model.previewAvatar.value, 'letter:最:#5856D6')
  assert.deepEqual(model.navigations, ['/chat', '/chat'])
})

test('profile nickname previews retain the chosen text colour and match the saved payload', async () => {
  const profile = await source('../src/components/chat/ProfileModal.vue')
  assert.match(profile, /:avatar="previewAvatar"/)
  assert.match(profile, /:avatar="textAvatarPreview"/)
  const script = profile.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const parsed = ts.createSourceFile('ProfileModal.ts', script, ts.ScriptTarget.Latest, true)
  const body = parsed.statements.filter(node => !ts.isImportDeclaration(node))
    .map(node => node.getText(parsed)).join('\n')
  const avatar = await source('../src/services/textAvatar.ts')
  const vue = new URL('../node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href
  const runtime = `
    import { computed, nextTick, shallowRef, watch } from '${vue}';
    const useTemplateRef = () => shallowRef(null);
    const defineProps = () => ({ open: true, user: { nickname: 'AvatarBefore', avatar: 'letter:A:#FF9500', username: 'test' } });
    const withDefaults = (value, defaults) => ({ ...defaults, ...value });
    export const saved = [];
    const defineEmits = () => (event, value) => saved.push({ event, value });
    const useToast = () => ({ push: () => {} });
    const isTextAvatarValue = isTextAvatar;
  `
  // The component has a computed called isTextAvatar, so alias the imported helper.
  const helper = avatar.replace('export function isTextAvatar(', 'export function isTextAvatarHelper(')
  const compiled = ts.transpileModule(runtime.replace('= isTextAvatar;', '= isTextAvatarHelper;') + helper + body
    + '\nexport { nickname, avatar, previewAvatar, textAvatarPreview, selectTextColor, saveProfile };', {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const model = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
  model.nickname.value = '  资料复验  '
  assert.equal(model.previewAvatar.value, 'letter:资:#FF9500')
  assert.equal(model.previewAvatar.value, model.textAvatarPreview.value)
  model.saveProfile()
  assert.deepEqual(model.saved[0], { event: 'save', value: { nickname: '资料复验', avatar: model.previewAvatar.value } })
  model.selectTextColor('#34C759')
  assert.equal(model.previewAvatar.value, 'letter:资:#34C759')
  model.avatar.value = '/uploads/retained.png'
  model.nickname.value = '图片'
  assert.equal(model.previewAvatar.value, '/uploads/retained.png')
  model.saveProfile()
  assert.equal(model.saved[1].value.avatar, '/uploads/retained.png')
})
