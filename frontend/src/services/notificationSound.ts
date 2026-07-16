let audioContext: AudioContext | null = null

function getAudioContext(): AudioContext | null {
    if (typeof window === 'undefined') return null

    try {
        audioContext ??= new AudioContext()
        return audioContext
    } catch {
        return null
    }
}

/**
 * 浏览器禁止网页在用户没有交互时自动播放声音。
 * 在第一次点击或按键时初始化并解锁 AudioContext。
 */
export function installNotificationSoundUnlock(): () => void {
    if (typeof window === 'undefined') {
        return () => undefined
    }

    const unlock = (): void => {
        const context = getAudioContext()

        if (context?.state === 'suspended') {
            void context.resume().catch(() => undefined)
        }
    }

    window.addEventListener('pointerdown', unlock, { once: true })
    window.addEventListener('keydown', unlock, { once: true })

    return () => {
        window.removeEventListener('pointerdown', unlock)
        window.removeEventListener('keydown', unlock)
    }
}

/**
 * 播放一段较轻的双音提示。
 * 不需要额外准备 mp3 文件。
 */
export function playNotificationSound(): void {
    const context = getAudioContext()

    if (!context || context.state !== 'running') return

    const startedAt = context.currentTime

    playTone(context, 784, startedAt, 0.12)
    playTone(context, 1046, startedAt + 0.1, 0.16)
}

function playTone(
    context: AudioContext,
    frequency: number,
    startedAt: number,
    duration: number,
): void {
    const oscillator = context.createOscillator()
    const gain = context.createGain()

    oscillator.type = 'sine'
    oscillator.frequency.setValueAtTime(frequency, startedAt)

    gain.gain.setValueAtTime(0.0001, startedAt)
    gain.gain.exponentialRampToValueAtTime(0.08, startedAt + 0.015)
    gain.gain.exponentialRampToValueAtTime(
        0.0001,
        startedAt + duration,
    )

    oscillator.connect(gain)
    gain.connect(context.destination)

    oscillator.start(startedAt)
    oscillator.stop(startedAt + duration)
}