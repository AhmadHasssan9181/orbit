# Orbit UI Asset Pack

This pack contains original Orbit themed assets for your Discord-style chat app.

## Included

- Brand SVGs
- Empty-state illustrations
- Modal illustrations
- Dark background patterns
- Lottie JSON animations
- Default user and server avatars
- Role badges
- Small WAV UI sounds
- CSS theme tokens

## Fast usage

Use these first in your current screen:

1. `illustrations/empty-direct-messages.svg`
2. `backgrounds/orbit-line-pattern.svg`
3. `brand/purple-glow.svg`
4. `animations/orbit-loader.json`
5. `animations/typing-dots.json`
6. `avatars/users/user-default-1.svg`
7. `theme/orbit-assets.css`

## React Lottie example

```tsx
import Lottie from "lottie-react";
import orbitLoader from "./assets/animations/orbit-loader.json";

export function Loader() {
  return <Lottie animationData={orbitLoader} loop />;
}
```

## Design notes

Use the SVG illustrations at 280px to 420px width for empty states. Keep opacity subtle and add a purple radial glow behind the illustration.

The assets are custom generated for Orbit and are not copied from Discord.
