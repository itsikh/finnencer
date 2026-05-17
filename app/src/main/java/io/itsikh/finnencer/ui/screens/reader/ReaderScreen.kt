package io.itsikh.finnencer.ui.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val prefs: ReaderPreferences,
) : ViewModel() {

    private val _payload = MutableStateFlow<ReaderHolder.Payload?>(ReaderHolder.take())
    val payload: StateFlow<ReaderHolder.Payload?> = _payload.asStateFlow()

    val fontStep: StateFlow<Int> = prefs.fontStep
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPreferences.DEFAULT_FONT_STEP)
    val theme: StateFlow<ReaderTheme> = prefs.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderTheme.DARK)

    fun setFontStep(step: Int) {
        viewModelScope.launch { prefs.setFontStep(step) }
    }

    fun setTheme(t: ReaderTheme) {
        viewModelScope.launch { prefs.setTheme(t) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit) {
    val vm: ReaderViewModel = hiltViewModel()
    val payload by vm.payload.collectAsState()
    val fontStep by vm.fontStep.collectAsState()
    val theme by vm.theme.collectAsState()

    val palette = themePalette(theme)
    var settingsOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var chromeVisible by remember { mutableStateOf(true) }

    // Keep the screen on while Reader Mode is in the foreground. Long-form
    // reading sessions easily exceed the default display timeout (15-60 s),
    // and the user explicitly asked Reader to suppress sleep. Cleared on
    // composition exit so we don't leak the flag to other screens.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Hide the top bar after the user starts scrolling down; show it again on
    // any upward scroll. Standard immersive-reader behavior.
    LaunchedEffect(scrollState) {
        var lastY = scrollState.value
        snapshotFlow { scrollState.value }.collect { y ->
            val delta = y - lastY
            if (kotlin.math.abs(delta) > 24) {
                chromeVisible = delta < 0 || y < 24
                lastY = y
            }
        }
    }

    Scaffold(
        containerColor = palette.background,
        contentColor = palette.text,
        topBar = {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                TopAppBar(
                    title = {
                        Text(
                            payload?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.text,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = palette.text)
                        }
                    },
                    actions = {
                        var shareMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { shareMenu = true }) {
                            Icon(Icons.Default.Share, "Share", tint = palette.text)
                        }
                        DropdownMenu(
                            expanded = shareMenu,
                            onDismissRequest = { shareMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share as text") },
                                leadingIcon = { Icon(Icons.Default.TextSnippet, null) },
                                onClick = {
                                    shareMenu = false
                                    payload?.let { p ->
                                        val attribution = p.attribution?.let { " ($it)" }.orEmpty()
                                        io.itsikh.finnencer.share.ShareHelpers.shareText(
                                            context,
                                            text = "${p.title}$attribution\n\n${p.body}",
                                            subject = p.title,
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share as PDF") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = {
                                    shareMenu = false
                                    payload?.let { p ->
                                        io.itsikh.finnencer.share.ShareHelpers.shareTextAsPdf(
                                            context = context,
                                            title = p.title,
                                            attribution = p.attribution,
                                            body = p.body,
                                            filename = p.title,
                                        )
                                    }
                                },
                            )
                        }
                        IconButton(onClick = { settingsOpen = !settingsOpen }) {
                            Icon(Icons.Default.FormatSize, "Reader settings", tint = palette.text)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.surface,
                        titleContentColor = palette.text,
                    ),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            // Right-side scroll indicator. Drawn on the OUTER (viewport-
            // sized) Box on purpose — drawing it on the verticalScroll'd
            // child would translate the bar by `-scrollState.value` along
            // with the prose, dragging it off-screen as the user scrolls.
            // size here is the viewport height; `barTop` already maps the
            // scroll position into viewport-space, so the bar tracks
            // progress visibly.
            .drawWithContent {
                drawContent()
                val max = scrollState.maxValue
                if (max > 0) {
                    val viewport = size.height
                    val total = viewport + max
                    val barHeight = (viewport * viewport / total).coerceAtLeast(48f)
                    val barTop = (scrollState.value / max.toFloat()) * (viewport - barHeight)
                    drawRoundRect(
                        color = palette.muted.copy(alpha = 0.65f),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width - 8f, barTop),
                        size = androidx.compose.ui.geometry.Size(4f, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                    )
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // Tap anywhere on body to toggle chrome — matches Pocket /
                    // iOS Reader. Without this, the top bar can vanish forever
                    // on a long article since the user stops scrolling.
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { chromeVisible = !chromeVisible },
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .padding(PaddingValues(horizontal = 22.dp, vertical = 24.dp)),
                ) {
                    payload?.let { p ->
                        Text(
                            p.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = palette.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        p.attribution?.takeIf { it.isNotBlank() }?.let { att ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                att,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.muted,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            p.body,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Default, // Inter / system serif fallback
                                fontSize = ReaderPreferences.FONT_STEPS[fontStep].sp,
                                lineHeight = (ReaderPreferences.FONT_STEPS[fontStep] * 1.55f).sp,
                                lineBreak = LineBreak.Paragraph,
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.None,
                                ),
                            ),
                            color = palette.text,
                        )
                        Spacer(Modifier.height(48.dp))
                    } ?: Text(
                        "Nothing to read.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.muted,
                    )
                }
            }

            if (settingsOpen) {
                ReaderSettingsCard(
                    palette = palette,
                    fontStep = fontStep,
                    theme = theme,
                    onFontStep = vm::setFontStep,
                    onTheme = vm::setTheme,
                    onDismiss = { settingsOpen = false },
                )
            }
        }
    }
}

@Composable
private fun ReaderSettingsCard(
    palette: ReaderPalette,
    fontStep: Int,
    theme: ReaderTheme,
    onFontStep: (Int) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { onDismiss() },
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 8.dp, end = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.surface)
                .border(1.dp, palette.muted.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { /* swallow taps */ }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "TEXT SIZE",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderPreferences.FONT_STEPS.forEachIndexed { idx, size ->
                    val selected = idx == fontStep
                    Box(
                        modifier = Modifier
                            .size(width = 38.dp, height = 38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) palette.accent.copy(alpha = 0.22f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (selected) palette.accent else palette.muted.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onFontStep(idx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "A",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) palette.accent else palette.text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (12 + idx * 2).sp,
                        )
                    }
                }
            }

            Text(
                "THEME",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { t ->
                    val selected = t == theme
                    val swatch = themePalette(t)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) palette.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (selected) palette.accent else palette.muted.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onTheme(t) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(swatch.background)
                                .border(1.dp, palette.muted.copy(alpha = 0.4f), RoundedCornerShape(7.dp)),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            when (t) {
                                ReaderTheme.DARK -> "Dark"
                                ReaderTheme.SEPIA -> "Sepia"
                                ReaderTheme.LIGHT -> "Light"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.text,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private data class ReaderPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
)

@Composable
private fun themePalette(t: ReaderTheme): ReaderPalette = when (t) {
    ReaderTheme.DARK -> ReaderPalette(
        background = Color(0xFF0B0F1F),
        surface = Color(0xFF161B2E),
        text = Color(0xFFE8ECF7),
        muted = Color(0xFF8892B0),
        accent = Color(0xFFA78BFA),
    )
    ReaderTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF5EEDC),
        surface = Color(0xFFE9E0C8),
        text = Color(0xFF3C2E1E),
        muted = Color(0xFF8A7656),
        accent = Color(0xFFB07A2E),
    )
    ReaderTheme.LIGHT -> ReaderPalette(
        background = Color(0xFFFAFAFB),
        surface = Color(0xFFFFFFFF),
        text = Color(0xFF1A1E2C),
        muted = Color(0xFF6E7691),
        accent = Color(0xFF6D5BD0),
    )
}
