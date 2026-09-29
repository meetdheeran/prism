package com.meetdheeran.prism.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.core.Provider
import com.meetdheeran.prism.core.SecureStore
import com.meetdheeran.prism.ui.glass.GlassBackground
import com.meetdheeran.prism.ui.glass.GlassStyle
import com.meetdheeran.prism.ui.glass.LiquidGlass
import com.meetdheeran.prism.ui.glass.rememberBackdropState
import com.meetdheeran.prism.ui.siri.Orb
import com.meetdheeran.prism.ui.siri.Phase
import com.meetdheeran.prism.ui.theme.LocalAccent
import com.meetdheeran.prism.ui.theme.PrismColors
import com.meetdheeran.prism.ui.theme.PrismTypography
import kotlinx.coroutines.launch

/** Four glass pages: hello, keys, permissions, done. Skippable; everything is reachable later in Settings. */
@Composable
fun OnboardingScreen(nav: NavController, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val graph = remember { AppGraph.get(ctx) }
    val scope = rememberCoroutineScope()
    val backdrop = rememberBackdropState()
    val accent = LocalAccent.current
    val pager = rememberPagerState { 4 }
    fun finish() { scope.launch { graph.prefs.update { it.copy(onboardingDone = true) }; onDone() } }

    Box(Modifier.fillMaxSize()) {
        GlassBackground(backdrop, accent = accent)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    when (page) {
                        0 -> {
                            Orb(size = 150.dp, phase = Phase.Idle)
                            Spacer(Modifier.height(28.dp))
                            Text(if (com.meetdheeran.prism.ui.theme.Appearance.nothing) "prism" else "Meet Prism", style = PrismTypography.displayMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("An assistant for your phone. Talk or type, ask about your screen, control the phone, and keep a memory you can edit.", style = PrismTypography.bodyLarge, color = PrismColors.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        1 -> {
                            Text(if (com.meetdheeran.prism.ui.theme.Appearance.nothing) "free ai" else "Free AI, your key", style = PrismTypography.headlineLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Prism runs on Gemini. Google gives every Google account a free key — it takes about a minute, and nobody pays anything.", style = PrismTypography.bodyLarge, color = PrismColors.TextSecondary)
                            Spacer(Modifier.height(20.dp))
                            Column(Modifier.fillMaxWidth()) { FreeKeySetup(backdrop) { scope.launch { pager.animateScrollToPage(2) } } }
                            Text("Already pay for Gemini, Groq or Claude? Add that key later in Settings → API keys → Advanced.", style = PrismTypography.bodySmall, color = PrismColors.TextTertiary)
                        }
                        2 -> {
                            Text("Only what you allow", style = PrismTypography.headlineLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Every permission is optional and explained: overlay for the control center and island, notification access for music, microphone for voice, Shizuku for the toggles Android reserves for the system.", style = PrismTypography.bodyLarge, color = PrismColors.TextSecondary)
                            Spacer(Modifier.height(20.dp))
                            GlassButton("Review permissions", Modifier.fillMaxWidth(), filled = false) { nav.navigate(Routes.PERMISSIONS) }
                        }
                        else -> {
                            Orb(size = 110.dp, phase = Phase.Thinking)
                            Spacer(Modifier.height(24.dp))
                            Text("Ready", style = PrismTypography.displayMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Provider: ${firstProviderWithKey(ctx).label}. Change anything later in Settings.", style = PrismTypography.bodyLarge, color = PrismColors.TextSecondary)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    repeat(4) { i ->
                        Box(Modifier.size(if (i == pager.currentPage) 10.dp else 7.dp).clip(CircleShape).background(if (i == pager.currentPage) PrismColors.TextPrimary else PrismColors.TextTertiary))
                        Spacer(Modifier.width(6.dp))
                    }
                }
                if (pager.currentPage < 3) {
                    GlassButton("Skip", filled = false) { finish() }
                    Spacer(Modifier.width(8.dp))
                    GlassButton("Next") { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
                } else {
                    GlassButton("Start") {
                        scope.launch {
                            val chosen = firstProviderWithKey(ctx)
                            graph.prefs.update { it.copy(provider = chosen) }
                            finish()
                        }
                    }
                }
            }
        }
    }
}


/** Gemini if it has a key (or nothing does), else whichever provider the user set up. */
private fun firstProviderWithKey(ctx: android.content.Context): Provider = when {
    SecureStore.has(ctx, SecureStore.KEY_GEMINI) -> Provider.GEMINI
    SecureStore.has(ctx, SecureStore.KEY_GROQ) -> Provider.GROQ
    SecureStore.has(ctx, SecureStore.KEY_CLAUDE) -> Provider.CLAUDE
    else -> Provider.GEMINI
}
