/*
 * Play Integrity Fix (inject-s) - ROM integration
 *
 * Copyright (C) 2026 wnduddld0513
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The TEESimulator page: the keybox the attestation service signs with, the apps it
 * applies to and the security patch it reports. State lives in the keybox attestation
 * service of the framework.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation. It is distributed WITHOUT ANY WARRANTY; see the GNU
 * General Public License (LICENSE) for details.
 */
package io.github.wnduddld0513.pif;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keybox.IKeyboxManager;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TeeSimulatorFragment extends SettingsBasePreferenceFragment {
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Preference> mTargetRows = new ArrayList<>();

    private ActivityResultLauncher<String[]> mPicker;
    private Preference mStatus;
    private Preference mFetchKeybox;
    private Preference mClear;
    private Preference mPatch;
    private PreferenceCategory mTargets;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.tee_settings, rootKey);
        mPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importKeybox);

        final MainSwitchPreference mainSwitch = findPreference("tee_enabled");
        mainSwitch.setChecked(KeyboxStore.isTeeEnabled(requireContext()));
        mainSwitch.addOnSwitchChangeListener((switchView, checked) -> {
            KeyboxStore.setTeeEnabled(requireContext(), checked);
            updateVisibility(checked);
        });
        updateVisibility(mainSwitch.isChecked());

        mStatus = findPreference("keybox_status");
        mClear = findPreference("keybox_clear");
        mTargets = findPreference("tee_targets");
        mPatch = findPreference("patch_value");

        findPreference("keybox_import").setOnPreferenceClickListener(p -> {
            mPicker.launch(new String[] {"text/xml", "application/xml", "text/plain"});
            return true;
        });
        mFetchKeybox = findPreference("keybox_fetch");
        mFetchKeybox.setOnPreferenceClickListener(p -> {
            fetchKeybox();
            return true;
        });
        mClear.setOnPreferenceClickListener(p -> {
            confirmClear();
            return true;
        });
        findPreference("target_add").setOnPreferenceClickListener(p -> {
            pickTarget();
            return true;
        });
        mPatch.setOnPreferenceClickListener(p -> {
            PatchDialog.show(requireContext(), null, this::refresh);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().setTitle(R.string.tee_title);
        refresh();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }

    /** The categories are shown while TEESimulator is on. */
    private void updateVisibility(boolean enabled) {
        for (String key : new String[] {"tee_keybox", "tee_targets", "tee_patch"}) {
            findPreference(key).setVisible(enabled);
        }
    }

    /** Reads the state of the service into the rows. */
    private void refresh() {
        final Context context = requireContext();
        final IKeyboxManager svc = KeyboxStore.service();
        for (String key : new String[] {"keybox_import", "keybox_fetch", "keybox_clear", "target_add", "patch_value"}) {
            findPreference(key).setEnabled(svc != null);
        }
        if (svc == null) {
            mStatus.setSummary(R.string.keybox_status_unavailable);
            return;
        }
        try {
            final JSONObject status = new JSONObject(svc.getKeyboxStatus());
            final boolean present = status.optBoolean("present");
            mClear.setEnabled(present);
            if (present) {
                final JSONArray algorithms = status.optJSONArray("algorithms");
                final List<String> names = new ArrayList<>();
                for (int i = 0; algorithms != null && i < algorithms.length(); i++) {
                    names.add(algorithms.getString(i));
                }
                mStatus.setSummary(context.getString(R.string.keybox_status_summary,
                        String.join(", ", names), status.optInt("ecCertCount"),
                        status.optInt("rsaCertCount"), status.optString("notAfter"),
                        context.getString(status.optBoolean("revoked")
                                ? R.string.keybox_revoked_yes : R.string.keybox_revoked_no)));
            } else {
                mStatus.setSummary(R.string.keybox_status_none);
            }
            refreshTargets(svc.getTargetApps());
            final String patch = svc.getSecurityPatch();
            mPatch.setSummary(patch.isEmpty() ? context.getString(R.string.patch_auto) : patch);
        } catch (Exception e) {
            mStatus.setSummary(R.string.keybox_status_unavailable);
        }
    }

    private void refreshTargets(List<String> packages) {
        for (Preference row : mTargetRows) {
            mTargets.removePreference(row);
        }
        mTargetRows.clear();
        final TreeMap<String, String> byLabel = new TreeMap<>();
        for (String pkg : packages) {
            byLabel.put(KeyboxStore.label(requireContext(), pkg) + "\u0000" + pkg, pkg);
        }
        for (String pkg : byLabel.values()) {
            final Preference row = new Preference(requireContext());
            row.setTitle(KeyboxStore.label(requireContext(), pkg));
            row.setSummary(pkg);
            row.setOrder(100);
            row.setOnPreferenceClickListener(p -> {
                confirmRemove(pkg);
                return true;
            });
            mTargets.addPreference(row);
            mTargetRows.add(row);
        }
    }

    /** Installs a keybox from Specter's catalog. */
    private void fetchKeybox() {
        final Context context = requireContext().getApplicationContext();
        mFetchKeybox.setEnabled(false);
        mExecutor.execute(() -> {
            boolean ok;
            try {
                KeyboxStore.fetchOnline(context, KeyboxStore.service());
                ok = true;
            } catch (Exception e) {
                Log.w("KeyboxStore", "Fetching a keybox failed", e);
                ok = false;
            }
            final boolean success = ok;
            mHandler.post(() -> {
                if (!isAdded()) return;
                mFetchKeybox.setEnabled(KeyboxStore.service() != null);
                Toast.makeText(context, success ? R.string.keybox_fetched
                        : R.string.keybox_fetch_failed, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void importKeybox(Uri uri) {
        if (uri == null) return;
        final Context context = requireContext().getApplicationContext();
        mExecutor.execute(() -> {
            String error = null;
            try {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    final byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                KeyboxStore.importKeybox(context, KeyboxStore.service(),
                        out.toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String reason = error;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(context, reason == null ? context.getString(R.string.keybox_imported)
                        : context.getString(R.string.keybox_import_failed, reason),
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.keybox_clear_message)
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    try {
                        KeyboxStore.clear(requireContext(), KeyboxStore.service());
                    } catch (Exception e) {
                        // The status row shows what the service holds
                    }
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmRemove(String pkg) {
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.target_remove_message, KeyboxStore.label(requireContext(), pkg)))
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    try {
                        final IKeyboxManager svc = KeyboxStore.service();
                        final List<String> packages = new ArrayList<>(svc.getTargetApps());
                        packages.remove(pkg);
                        svc.setTargetApps(packages);
                    } catch (Exception e) {
                        // The list shows what the service holds
                    }
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Lists the launchable apps that are not targets yet. */
    private void pickTarget() {
        final IKeyboxManager svc = KeyboxStore.service();
        final PackageManager pm = requireContext().getPackageManager();
        final List<String> current;
        try {
            current = new ArrayList<>(svc.getTargetApps());
        } catch (Exception e) {
            return;
        }
        final TreeMap<String, String> byLabel = new TreeMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            final String pkg = info.activityInfo.packageName;
            if (!current.contains(pkg)) {
                byLabel.put(info.loadLabel(pm) + "\u0000" + pkg, pkg);
            }
        }
        final List<String> packages = new ArrayList<>(byLabel.values());
        final String[] labels = new String[packages.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = KeyboxStore.label(requireContext(), packages.get(i));
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.target_add_title)
                .setItems(labels, (dialog, which) -> {
                    current.add(packages.get(which));
                    try {
                        svc.setTargetApps(current);
                    } catch (Exception e) {
                        // The list shows what the service holds
                    }
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
