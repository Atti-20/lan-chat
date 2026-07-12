# Design System: LanChat

## 1. Visual Theme & Atmosphere

A grounded, daily-app-balanced chat interface with offset-asymmetric layout sensibility and fluid CSS motion. The atmosphere is warm yet clinical — like a well-lit reading room with soft daylight. Density sits at 5/10: enough breathing room for conversations to feel comfortable, but dense enough for information-rich sidebars and member lists. Variance at 6/10: the layout breaks symmetry through offset panels and staggered message bubbles, avoiding the generic "centered everything" chat template. Motion at 5/10: subtle spring-based micro-interactions on hover, focus, and message arrival — no cinematic choreography, just tactile responsiveness.

## 2. Color Palette & Roles

- **Canvas Mist** (#F4F5F7) — Primary background surface, the app shell
- **Pure Surface** (#FFFFFF) — Cards, message bubbles (self), input areas, modals
- **Charcoal Ink** (#1A1D21) — Primary text, Zinc-950 depth, never pure black
- **Muted Slate** (#6B7280) — Secondary text, timestamps, metadata, descriptions
- **Faint Stone** (#9CA3AF) — Tertiary text, placeholder content, disabled states
- **Whisper Border** (#E5E7EB) — 1px structural lines, dividers, card borders
- **Soft Hover** (#F0F1F3) — Hover background for list items, buttons ghost state
- **Emerald Pulse** (#059669) — Single accent: online status, primary CTAs, active tab indicator, unread badge, send button. Saturation 62%, no neon.
- **Amber Glow** (#D97706) — Warning states: muted indicator, expiring requests, mute period active. Saturation 68%.
- **Rose Mute** (#E11D48) — Destructive actions: delete, dissolve, error text. Saturation 72%, used sparingly.
- **Self Bubble** (#DCF2E9) — Self message bubble background, a desaturated emerald tint
- **Peer Bubble** (#FFFFFF) — Peer message bubble, pure surface with whisper border

No purple. No neon. No gradient buttons. No pure black.

## 3. Typography Rules

- **Display:** Satoshi — Track-tight, controlled scale, weight-driven hierarchy. Used for app title, section headers.
- **Body:** Satoshi — Relaxed leading (1.6), 65ch max-width, neutral secondary color for descriptions. Used for all UI text, message content, form labels.
- **Mono:** JetBrains Mono — For timestamps, file sizes, technical metadata, message IDs in dev views.
- **Scale:** 0.8125rem (13px) body / 0.75rem (12px) caption / 1rem (16px) section title / 1.5rem (24px) page title
- **Banned:** Inter, Roboto, system-ui as primary font. Generic serif fonts (Times New Roman, Georgia, Garamond, Palatino). All serif fonts banned — this is a software UI.

## 4. Component Stylings

**Buttons:**
- Primary: Emerald Pulse fill (#059669), Pure Surface text, 0.5rem radius, no outer glow. Tactile -1px translateY on active state. 0.625rem 1.25rem padding.
- Secondary: ghost/outline with Whisper Border, Charcoal Ink text, Soft Hover on hover.
- Icon buttons: 2rem square, transparent background, Soft Hover on hover, 0.375rem radius.
- Destructive: Rose Mute text on ghost, Rose Mute fill on confirm.

**Cards:**
- Message bubbles: 0.75rem radius with single corner flattened (asymmetric — 0.25rem on sender side). Self bubbles use Self Bubble tint; peer bubbles use Pure Surface with Whisper Border.
- Sidebar items: no card elevation. 0.5rem radius on hover/active state with Soft Hover background.
- Modals: 1rem radius, Pure Surface fill, Whisper Border 1px, diffused shadow (0 8px 32px rgba(26,29,33,0.08)).

**Inputs:**
- Label above input, Charcoal Ink weight 500. Helper text in Muted Slate below. Error text in Rose Mute below.
- Focus ring: 2px Emerald Pulse at 30% opacity, no outer glow.
- Input fill: Pure Surface, Whisper Border 1px, 0.5rem radius, 0.625rem 0.875rem padding.
- No floating labels.

**Loaders:**
- Skeletal shimmer matching exact layout dimensions. Message list skeleton: repeating gray bars with animation. Sidebar skeleton: circular avatar placeholder + two text bars. No circular spinners.

**Empty States:**
- Composed illustration-style compositions using CSS shapes and Muted Slate text. "Select a conversation to start chatting" with a subtle geometric arrangement, not just text.

**Avatars:**
- 2.5rem default, 2rem in dense lists, 3rem in profiles. Circular. Fallback: Charcoal Ink background with Pure Surface initials. Online indicator: 0.625rem Emerald Pulse dot at bottom-right with 2px Pure Surface ring.

## 5. Layout Principles

- Three-panel architecture: Navigation rail (4rem fixed) + Conversation list (18rem fixed, collapsible to 0 on mobile) + Chat area (flex-1).
- CSS Grid for the main shell: `grid-template-columns: 4rem 18rem 1fr`.
- No flexbox percentage math. No `calc()` hacks.
- Max-width containment not needed — chat app fills viewport.
- Full-height sections use `min-h-[100dvh]` — never `h-screen`.
- Mobile: Navigation rail becomes bottom bar (3.5rem), conversation list becomes full-width overlay, chat area full-width. Single column below 768px.
- Generous internal padding: 1rem standard, 0.625rem compact, 1.5rem spacious.
- No overlapping elements. No absolute-positioned stacking.

## 6. Motion & Interaction

- Spring physics: `cubic-bezier(0.34, 1.56, 0.64, 1)` for bouncy elements (message arrival, modal entrance). `cubic-bezier(0.4, 0, 0.2, 1)` for standard transitions (hover, focus, tab switch).
- Duration: 150ms for micro-interactions, 250ms for panel transitions, 300ms for modal entrance.
- Message arrival: translateY(8px) + opacity(0) to translateY(0) + opacity(1), 250ms spring.
- Staggered orchestration: conversation list items cascade-mount with 30ms delay each.
- Perpetual micro-interaction: online status dot has a subtle pulse animation (opacity 1 to 0.4, 2s infinite).
- Typing indicator: three dots with staggered bounce, 1.4s infinite loop.
- Performance: animate exclusively via `transform` and `opacity`. Never animate `top`, `left`, `width`, `height`.
- Grain/noise: not used — keep the interface clean and clinical.

## 7. Responsive Rules

- **Mobile-First Collapse (< 768px):** Three-panel collapses to single panel with swipe-back navigation. Navigation rail becomes bottom tab bar. Conversation list becomes full-width slide-in panel. Chat area takes full viewport.
- **No Horizontal Scroll:** Critical failure if horizontal overflow occurs at any breakpoint.
- **Typography Scaling:** Section titles use `clamp(1rem, 2vw, 1.5rem)`. Body text fixed at 0.8125rem (13px) minimum.
- **Touch Targets:** All interactive elements minimum 44px tap target. Icon buttons enlarged on mobile.
- **Navigation:** Desktop left rail collapses to bottom bar on mobile with 5 tabs maximum.
- **Spacing:** Vertical section gaps reduce: `clamp(0.5rem, 2vw, 1rem)`.

## 8. Anti-Patterns (Banned)

- No emojis anywhere in the UI chrome (only in user-generated message content)
- No `Inter` font
- No generic serif fonts (Times New Roman, Georgia, Garamond, Palatino)
- No pure black (`#000000`) — use Charcoal Ink (#1A1D21)
- No neon or outer glow shadows
- No oversaturated accent colors
- No excessive gradient text on headers
- No custom mouse cursors
- No overlapping elements — clean spatial separation always
- No 3-column equal card layouts
- No generic placeholder names ("John Doe", "Acme", "Nexus")
- No fake round numbers (99.99%, 50%)
- No AI copywriting clichés ("Elevate", "Seamless", "Unleash", "Next-Gen")
- No filler UI text ("Scroll to explore", "Swipe down", scroll arrows, bouncing chevrons)
- No broken Unsplash links — use SVG avatars or `picsum.photos`
- No centered hero sections (this is an app, not a landing page)
- No circular spinners for loading states — use skeletal shimmer
