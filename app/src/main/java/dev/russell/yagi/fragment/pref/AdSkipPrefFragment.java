package dev.russell.yagi.fragment.pref;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.collection.ArrayMap;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import org.greenrobot.eventbus.EventBus;

import java.util.Map;

import dev.russell.yagi.R;
import dev.russell.yagi.activity.SettingsActivity;
import dev.russell.yagi.common.util.TopSnackbarUtil;
import dev.russell.yagi.common.views.AccentMaterialDialog;
import dev.russell.yagi.core.adskip.ClaudeAdClient;
import dev.russell.yagi.core.adskip.ClaudeException;
import dev.russell.yagi.core.pref.Prefs;
import dev.russell.yagi.core.service.download.PodcastHttpClient;
import dev.russell.yagi.databinding.ClaudeApiKeyDialogBinding;
import dev.russell.yagi.event.settings.AdSkipChangedEvent;
import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * The global ad-skip defaults. Each podcast can override "Detect ads" in its own settings, so
 * the other options here matter even while the global switch is off.
 */
public class AdSkipPrefFragment extends AbsSettingsFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "AdSkipPrefFragment";

    /** The Claude API key entry; not persisted itself, the key is kept by {@link Prefs}. */
    private static final String PREF_CLAUDE_KEY = "pref_ad_skip_claude_key";

    @Nullable
    private AlertDialog claudeKeyDialog;
    @Nullable
    private Disposable claudeKeyCheck;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pref_ad_skip);
        buildAdSkipSensitivityPreference();
        Preference claudeKey = findPreference(PREF_CLAUDE_KEY);
        if (claudeKey != null) {
            claudeKey.setOnPreferenceClickListener(preference -> {
                showClaudeKeyDialog();
                return true;
            });
        }
        updateClaudeKeySummary();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((SettingsActivity) getActivity()).setTitle(R.string.pref_ad_skip_category);
        PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // The check keeps running while the screen is only in the background, so a key typed
        // before switching apps is still checked and saved; it ends with the screen.
        if (claudeKeyCheck != null) {
            claudeKeyCheck.dispose();
            claudeKeyCheck = null;
        }
        if (claudeKeyDialog != null) {
            claudeKeyDialog.dismiss();
            claudeKeyDialog = null;
        }
    }

    private void updateClaudeKeySummary() {
        Preference pref = findPreference(PREF_CLAUDE_KEY);
        if (pref != null) {
            pref.setSummary(Prefs.hasClaudeApiKey()
                    ? R.string.pref_ad_skip_claude_key_sum_set
                    : R.string.pref_ad_skip_claude_key_sum_unset);
        }
    }

    /**
     * Asks for the Anthropic API key. Save checks the key with Anthropic first: a key Anthropic
     * rejects is not saved and stays in the field to be corrected, while a check that cannot be
     * made (offline, Anthropic unreachable) saves the key anyway and says so. An empty field
     * saved over an existing key removes it, like Remove.
     */
    private void showClaudeKeyDialog() {
        final boolean hadKey = Prefs.hasClaudeApiKey();
        final ClaudeApiKeyDialogBinding binding =
                ClaudeApiKeyDialogBinding.inflate(LayoutInflater.from(requireContext()));
        String current = Prefs.getClaudeApiKey();
        if (current != null) {
            binding.claudeKey.setText(current);
            binding.claudeKey.setSelection(current.length());
        }
        AlertDialog.Builder builder = new AccentMaterialDialog(requireContext(), R.style.MaterialAlertDialogTheme)
                .setTitle(R.string.pref_ad_skip_claude_key_title)
                .setMessage(R.string.claude_api_key_dialog_message)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.save_label, null)
                .setNegativeButton(R.string.cancel_label, null);
        if (hadKey) {
            builder.setNeutralButton(R.string.claude_api_key_remove, (dialog, which) -> {
                Prefs.setClaudeApiKey(null);
                onClaudeKeyChanged(R.string.claude_api_key_removed);
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (claudeKeyDialog == d) {
                claudeKeyDialog = null;
            }
            if (claudeKeyCheck != null) {
                claudeKeyCheck.dispose();
                claudeKeyCheck = null;
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        claudeKeyDialog = dialog;
        dialog.show();
        binding.claudeKey.requestFocus();
        binding.claudeKey.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.claudeKeyLayout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        // Set after show() so that Save does not close the dialog before the check is done.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            CharSequence text = binding.claudeKey.getText();
            String key = text == null ? "" : text.toString().trim();
            if (key.isEmpty()) {
                if (hadKey) {
                    Prefs.setClaudeApiKey(null);
                    onClaudeKeyChanged(R.string.claude_api_key_removed);
                }
                dialog.dismiss();
                return;
            }
            checkAndSaveClaudeKey(dialog, binding, key);
        });
    }

    private void checkAndSaveClaudeKey(@NonNull AlertDialog dialog,
                                       @NonNull ClaudeApiKeyDialogBinding binding,
                                       @NonNull String key) {
        if (claudeKeyCheck != null) {
            claudeKeyCheck.dispose();
        }
        setDialogBusy(dialog, binding, true);
        binding.claudeKeyLayout.setError(null);
        binding.claudeKeyLayout.setHelperText(getString(R.string.claude_api_key_checking));
        claudeKeyCheck = Completable.fromAction(
                        () -> new ClaudeAdClient(PodcastHttpClient.getHttpClient(), key).checkKey())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    claudeKeyCheck = null;
                    Prefs.setClaudeApiKey(key);
                    dialog.dismiss();
                    onClaudeKeyChanged(R.string.claude_api_key_saved);
                }, error -> {
                    claudeKeyCheck = null;
                    if (error instanceof ClaudeException && ((ClaudeException) error).isKeyRejected()) {
                        setDialogBusy(dialog, binding, false);
                        binding.claudeKeyLayout.setHelperText(null);
                        binding.claudeKeyLayout.setError(getString(R.string.claude_api_key_rejected));
                        return;
                    }
                    // Not a verdict on the key (offline, Anthropic unreachable or overloaded):
                    // keep it, the analysis reports a bad key later anyway.
                    Log.w(TAG, "Could not check the Claude API key", error);
                    Prefs.setClaudeApiKey(key);
                    dialog.dismiss();
                    onClaudeKeyChanged(R.string.claude_api_key_saved_unchecked);
                });
    }

    private static void setDialogBusy(@NonNull AlertDialog dialog,
                                      @NonNull ClaudeApiKeyDialogBinding binding, boolean busy) {
        binding.claudeKey.setEnabled(!busy);
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save != null) {
            save.setEnabled(!busy);
        }
        Button remove = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (remove != null) {
            remove.setEnabled(!busy);
        }
    }

    private void onClaudeKeyChanged(@StringRes int messageRes) {
        updateClaudeKeySummary();
        // 0 means "the global settings changed", not one feed's switch
        EventBus.getDefault().post(new AdSkipChangedEvent(0));
        TopSnackbarUtil.showSnack(getActivity(), messageRes, Toast.LENGTH_SHORT);
    }

    /** The list preference shows the chosen entry in its summary, both on open and after every change. */
    private void buildAdSkipSensitivityPreference() {
        final Resources res = requireActivity().getResources();
        final Map<String, String> options = new ArrayMap<>();
        String[] keys = res.getStringArray(R.array.ad_skip_sensitivity_values);
        String[] values = res.getStringArray(R.array.ad_skip_sensitivity_options);
        for (int i = 0; i < keys.length; i++) {
            options.put(keys[i], values[i]);
        }

        ListPreference pref = findPreference(Prefs.PREF_AD_SKIP_SENSITIVITY);
        if (pref == null) {
            throw new IllegalArgumentException("Preference with key '"
                    + Prefs.PREF_AD_SKIP_SENSITIVITY + "' is not found");
        }
        pref.setSummary(res.getString(R.string.pref_ad_skip_sensitivity_sum, options.get(pref.getValue())));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!(newValue instanceof String)) {
                return false;
            }
            pref.setSummary(res.getString(R.string.pref_ad_skip_sensitivity_sum,
                    options.get((String) newValue)));
            return true;
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Prefs.PREF_AD_SKIP_ENABLED.equals(key)
                || Prefs.PREF_AD_SKIP_SENSITIVITY.equals(key)
                || Prefs.PREF_AD_SKIP_ANALYZE_ON_DOWNLOAD.equals(key)
                || Prefs.PREF_AD_SKIP_SHOW_SNACKBAR.equals(key)
                || Prefs.PREF_AD_SKIP_USE_TRANSCRIPTS.equals(key)
                || Prefs.PREF_AD_SKIP_SELF_PROMO.equals(key)) {
            // 0 means "the global settings changed", not one feed's switch
            EventBus.getDefault().post(new AdSkipChangedEvent(0));
        }
    }

    @Override
    public void invalidateSettings() {

    }
}
