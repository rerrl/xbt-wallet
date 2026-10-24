package com.xbtwallet.app.ui.theme

import androidx.compose.ui.graphics.Color

// Dark-first palette for a coordinator wallet: calm, low-glare, no branding noise.
val XbtPrimary = Color(0xFFF7931A) // bitcoin orange
val XbtOnPrimary = Color(0xFF1A1206)
val XbtSurfaceDark = Color(0xFF101418)
val XbtOnSurfaceDark = Color(0xFFE6E8EB)
val XbtSurfaceVariantDark = Color(0xFF2A2F36)
val XbtOnSurfaceVariantDark = Color(0xFFB6BCC4)
val XbtBackgroundDark = Color(0xFF0B0E11)
val XbtOnBackgroundDark = Color(0xFFE6E8EB)
val XbtOutlineDark = Color(0xFF3A4048)
val XbtErrorDark = Color(0xFFFF6B6B)
val XbtSuccessDark = Color(0xFF4CAF50) // "connected" dot on the home screen
val XbtWarningDark = Color(0xFFE0A800) // non-fatal warnings, never the success path

// The tag accent for a key that came off a signing device that is *not* a
// SeedSigner (Keystone, Jade): calmly distinct from the orange that means
// SeedSigner and from the grey that means a plain text xpub, and never mistakable
// for the green/amber/red of a status.
val XbtDeviceTagDark = Color(0xFF5B9BD5) // steel blue

// The tag accent for a hot wallet — a seed on this phone. Amber, and the one tag
// that is a caution rather than a description of where a key lives: this wallet
// can be spent from the device in the user's hand. Distinct from the bitcoin
// orange that the SeedSigner tag wears (and from XbtWarningDark above, which is a
// status colour and means "something needs attention"), so the two tags cannot be
// mistaken for each other at a glance.
val XbtHotTagDark = Color(0xFFFFB300) // amber
