package com.verisphere.app.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VSSpacing
import com.verisphere.app.ui.theme.VSTypography
import com.verisphere.app.ui.theme.VeriSphereTheme
import com.verisphere.app.util.GeminiKeyValidation

/**
 * Story 10.1 — Paramètres tab content.
 *
 * **WisprFlow editorial layout** — mirrors
 * [com.verisphere.app.ui.onboarding.AccessibilityExplanationScreen] :
 * canvas background, vertical scroll with bottom-anchored CTA, editorial
 * serif heading, Figtree body, full-width sage Save button (52 dp height /
 * 14 dp corners). Section subtitle "Clés API" uses the post-2026-05-19
 * "bold + 13sp" override applied to `labelTrackedSans` (same pattern as
 * the Synthèse Google subtitle refactor in commit `53eb558`).
 *
 * **First-launch banner** — when [showFirstLaunchBanner] is true (set by
 * MainActivity when `userGeminiKey.isBlank()`), a sage-tinted callout
 * surfaces above the title prompting the user to configure their key.
 * The banner is state-driven : the moment the user saves a valid key,
 * MainActivity flips the flag false and the banner unmounts.
 *
 * **Stateless callbacks** — same pattern as Story 1.8.5 / 5.1 / 7.5 :
 * the composable owns layout + ViewModel observation ; side effects
 * (snackbar host event consumption, MainActivity callback) flow back
 * via [onKeysSaved].
 *
 * @param onKeysSaved Invoked after a successful save — MainActivity
 *   re-reads `userGeminiKey` to drop the first-launch banner.
 * @param showFirstLaunchBanner When `true`, renders the
 *   `settings_first_launch_banner` callout at the top.
 * @param modifier Outer modifier — typically `Modifier.fillMaxSize()`
 *   inherited from the AppShell content slot.
 * @param viewModel ViewModel factory wires the orchestrator from
 *   `application.container.onboardingOrchestrator`.
 */
@Composable
fun SettingsScreen(
    onKeysSaved: () -> Unit,
    showFirstLaunchBanner: Boolean,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.settings_keys_saved)

    // Consume one-shot events emitted by the ViewModel after a
    // successful persistence write : surface the snackbar + propagate
    // up to MainActivity so the first-launch banner can dismiss.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.KeysSaved -> {
                    snackbarHostState.showSnackbar(message = savedMessage)
                    onKeysSaved()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VSPalette.canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = VSPalette.accentSageDeep)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = VSSpacing.space32,
                    vertical = VSSpacing.space40,
                )
                .heightIn(min = SETTINGS_SCREEN_MIN_CONTENT_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showFirstLaunchBanner) {
                FirstLaunchBanner()
                Spacer(modifier = Modifier.height(VSSpacing.space24))
            }

            Text(
                text = stringResource(R.string.settings_screen_title),
                style = VSTypography.headlineSerif,
                color = VSPalette.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = SETTINGS_TITLE_MAX_WIDTH_DP.dp),
            )

            Spacer(modifier = Modifier.height(VSSpacing.space32))

            Text(
                text = stringResource(R.string.settings_section_api_keys),
                style = VSTypography.labelTrackedSans.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
                color = VSPalette.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_SECTION_API_KEYS),
            )

            Spacer(modifier = Modifier.height(VSSpacing.space16))

            ApiKeyField(
                label = stringResource(R.string.settings_gemini_key_label),
                hint = stringResource(R.string.settings_gemini_key_hint),
                helperText = stringResource(R.string.settings_gemini_key_helper),
                value = state.geminiKeyDraft,
                onValueChange = viewModel::onGeminiKeyChange,
                visible = state.geminiKeyVisible,
                onToggleVisibility = viewModel::toggleGeminiVisibility,
                errorMessage = geminiErrorMessageFor(state.geminiValidation),
                // Polish 2026-05-20 — surface "✓ Sauvegardée" when the
                // current draft matches the persisted value (and is
                // non-empty). Editing the field flips this off until
                // the next successful save.
                isSaved = state.geminiKeyDraft.isNotEmpty() &&
                    state.geminiKeyDraft == state.persistedGeminiKey,
                testTag = TAG_GEMINI_KEY_FIELD,
            )

            Spacer(modifier = Modifier.height(VSSpacing.space20))

            ApiKeyField(
                label = stringResource(R.string.settings_serp_key_label),
                hint = "",
                helperText = stringResource(R.string.settings_serp_key_helper),
                value = state.serpKeyDraft,
                onValueChange = viewModel::onSerpKeyChange,
                visible = state.serpKeyVisible,
                onToggleVisibility = viewModel::toggleSerpVisibility,
                errorMessage = null,
                isSaved = state.serpKeyDraft.isNotEmpty() &&
                    state.serpKeyDraft == state.persistedSerpKey,
                testTag = TAG_SERP_KEY_FIELD,
            )

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(VSSpacing.space32))

            Button(
                onClick = viewModel::onSave,
                // P8 — gate on `!isLoading` too so the user cannot trigger
                // a save while the init coroutine is still seeding the
                // drafts from SecureStorage. Otherwise a fast Save tap
                // during the 50-200ms Keystore warmup could overwrite a
                // previously-stored key with an empty draft (D1 race).
                // Defensive : current code already early-returns the
                // spinner during isLoading, so the button isn't even
                // composed — but kept here for future refactor safety.
                enabled = !state.saveInFlight && !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(TAG_SAVE_BUTTON),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VSPalette.accentSageDeep,
                    contentColor = VSPalette.onAccentSageDeep,
                ),
            ) {
                Text(
                    text = stringResource(R.string.settings_save_cta),
                    style = VSTypography.headlineBodySans,
                )
            }
        }
    }
}

/**
 * Sage-tinted callout shown above the title on first launch (no Gemini
 * key configured yet). Drops itself when `userGeminiKey` becomes
 * non-blank in MainActivity (state-driven, not animated).
 */
@Composable
private fun FirstLaunchBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_FIRST_LAUNCH_BANNER),
        shape = RoundedCornerShape(12.dp),
        color = VSPalette.accentSage.copy(alpha = 0.18f),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = VSSpacing.space16,
                vertical = VSSpacing.space12,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VSSpacing.space12),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = VSPalette.accentSageDeep,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.settings_first_launch_banner),
                style = VSTypography.bodySans,
                color = VSPalette.ink,
            )
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    hint: String,
    helperText: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    errorMessage: String?,
    isSaved: Boolean,
    testTag: String,
) {
    val isError = errorMessage != null
    val visibilityIcon = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
    val visibilityContentDescription = if (visible) {
        stringResource(R.string.settings_hide_key)
    } else {
        stringResource(R.string.settings_show_key)
    }
    val savedIndicatorText = stringResource(R.string.settings_key_saved_indicator)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        placeholder = if (hint.isNotEmpty()) {
            { Text(text = hint, color = VSPalette.inkSoft) }
        } else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Password,
        ),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = visibilityIcon,
                    contentDescription = visibilityContentDescription,
                    tint = VSPalette.inkMuted,
                )
            }
        },
        supportingText = {
            // Priority order for the supporting line :
            //   1. Error (validation failed) → red helper.
            //   2. Saved (draft == persisted + non-empty) → green
            //      "✓ Sauvegardée" indicator using accentSageDeep
            //      (the brand "validated" colour used by the CTA).
            //   3. Default → helper link text in inkSoft.
            val displayText = when {
                isError -> errorMessage ?: helperText
                isSaved -> savedIndicatorText
                else -> helperText
            }
            val displayColor = when {
                isError -> VSPalette.verdictFalse
                isSaved -> VSPalette.accentSageDeep
                else -> VSPalette.inkSoft
            }
            Text(
                text = displayText,
                style = VSTypography.bodySans,
                color = displayColor,
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VSPalette.accentSageDeep,
            focusedLabelColor = VSPalette.accentSageDeep,
            cursorColor = VSPalette.accentSageDeep,
            unfocusedBorderColor = VSPalette.hairline,
            unfocusedLabelColor = VSPalette.inkMuted,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

@Composable
private fun geminiErrorMessageFor(validation: GeminiKeyValidation?): String? = when (validation) {
    null, GeminiKeyValidation.Valid -> null
    GeminiKeyValidation.Empty -> stringResource(R.string.settings_gemini_key_required)
}

private const val SETTINGS_SCREEN_MIN_CONTENT_DP: Int = 600
private const val SETTINGS_TITLE_MAX_WIDTH_DP: Int = 320

internal const val TAG_SECTION_API_KEYS: String = "vs_settings_section_api_keys"
internal const val TAG_GEMINI_KEY_FIELD: String = "vs_settings_gemini_key_field"
internal const val TAG_SERP_KEY_FIELD: String = "vs_settings_serp_key_field"
internal const val TAG_SAVE_BUTTON: String = "vs_settings_save_button"
internal const val TAG_FIRST_LAUNCH_BANNER: String = "vs_settings_first_launch_banner"

@Preview(showBackground = true, name = "First launch Light")
@Composable
private fun SettingsScreenFirstLaunchPreview() {
    VeriSphereTheme {
        SettingsScreen(onKeysSaved = {}, showFirstLaunchBanner = true)
    }
}

@Preview(showBackground = true, name = "First launch Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenFirstLaunchDarkPreview() {
    VeriSphereTheme {
        SettingsScreen(onKeysSaved = {}, showFirstLaunchBanner = true)
    }
}

@Preview(showBackground = true, name = "Configured Light")
@Composable
private fun SettingsScreenConfiguredPreview() {
    VeriSphereTheme {
        SettingsScreen(onKeysSaved = {}, showFirstLaunchBanner = false)
    }
}
