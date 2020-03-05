package org.meowcat.edxposed.manager;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import org.meowcat.edxposed.manager.repo.Module;
import org.meowcat.edxposed.manager.repo.ModuleVersion;
import org.meowcat.edxposed.manager.repo.ReleaseType;
import org.meowcat.edxposed.manager.repo.RepoDb;
import org.meowcat.edxposed.manager.util.DownloadsUtil;
import org.meowcat.edxposed.manager.util.InstallApkUtil;
import org.meowcat.edxposed.manager.util.ModuleUtil;
import org.meowcat.edxposed.manager.util.NavUtil;
import org.meowcat.edxposed.manager.util.RepoLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;

public class ModulesActivity extends BaseActivity implements ModuleUtil.ModuleListener {

    public static final String SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS";
    private int installedXposedVersion;
    private ApplicationFilter filter;
    private SearchView mSearchView;
    private SearchView.OnQueryTextListener mSearchListener;
    private PackageManager mPm;
    private DateFormat dateformat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private ModuleUtil mModuleUtil;
    private ModuleAdapter mAdapter = null;
    private RecyclerView mListView;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private Runnable reloadModules = new Runnable() {
        public void run() {
            String queryStr = mSearchView != null ? mSearchView.getQuery().toString() : "";
            Collection<ModuleUtil.InstalledModule> showList;
            Collection<ModuleUtil.InstalledModule> fullList = mModuleUtil.getModules().values();
            if (queryStr.length() == 0) {
                showList = fullList;
            } else {
                showList = new ArrayList<>();
                String filter = queryStr.toLowerCase();
                for (ModuleUtil.InstalledModule info : fullList) {
                    if (lowercaseContains(InstallApkUtil.getAppLabel(info.app, mPm), filter)
                            || lowercaseContains(info.packageName, filter)) {
                        showList.add(info);
                    }
                }
            }
            mAdapter.addAll(showList);
            mAdapter.notifyDataSetChanged();
            mModuleUtil.updateModulesList(false);
            mSwipeRefreshLayout.setRefreshing(false);
        }
    };
    private String selectedPackageName;

    private void filter(String constraint) {
        filter.filter(constraint);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_modules);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
        }
        setupWindowInsets();
        filter = new ApplicationFilter();
        mModuleUtil = ModuleUtil.getInstance();
        mPm = getPackageManager();
        installedXposedVersion = XposedApp.getXposedVersion();
        if (installedXposedVersion <= 0) {
            Snackbar.make(findViewById(R.id.snackbar), R.string.xposed_not_active, Snackbar.LENGTH_LONG).setAction(R.string.Settings, v -> {
                Intent intent = new Intent();
                intent.setClass(ModulesActivity.this, SettingsActivity.class);
                startActivity(intent);
            }).show();
        }
        mAdapter = new ModuleAdapter();
        mModuleUtil.addListener(this);
        mListView = findViewById(R.id.recyclerView);
        mListView.setAdapter(mAdapter);
        mListView.setLayoutManager(new LinearLayoutManager(this));
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mListView.getContext(),
                DividerItemDecoration.VERTICAL);
        mListView.addItemDecoration(dividerItemDecoration);
        mSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        mSwipeRefreshLayout.setOnRefreshListener(() -> reloadModules.run());
        reloadModules.run();
        mSearchListener = new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filter(query);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filter(newText);
                return false;
            }
        };

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_modules, menu);
        mSearchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        mSearchView.setOnQueryTextListener(mSearchListener);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == 42) {
            File listModules = new File(XposedApp.ENABLED_MODULES_LIST_FILE);
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            FileInputStream in = new FileInputStream(listModules);
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = in.read(buffer)) > 0) {
                                os.write(buffer, 0, len);
                            }
                            os.close();
                        }
                    } catch (Exception e) {
                        Snackbar.make(findViewById(R.id.snackbar), getResources().getString(R.string.logs_save_failed) + "\n" + e.getMessage(), Snackbar.LENGTH_LONG).show();
                    }
                }
            }
        } else if (requestCode == 43) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            PrintWriter fileOut = new PrintWriter(os);

                            Set<String> keys = ModuleUtil.getInstance().getModules().keySet();
                            for (String key1 : keys) {
                                fileOut.println(key1);
                            }
                            fileOut.close();
                            os.close();
                        }
                    } catch (Exception e) {
                        Snackbar.make(findViewById(R.id.snackbar), getResources().getString(R.string.logs_save_failed) + "\n" + e.getMessage(), Snackbar.LENGTH_LONG).show();
                    }
                }
            }
        } else if (requestCode == 44) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        importModules(uri);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.export_enabled_modules:
                if (ModuleUtil.getInstance().getEnabledModules().isEmpty()) {
                    Snackbar.make(findViewById(R.id.snackbar), R.string.no_enabled_modules, Snackbar.LENGTH_SHORT).show();
                    return false;
                }
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/*");
                intent.putExtra(Intent.EXTRA_TITLE, "enabled_modules.list");
                startActivityForResult(intent, 42);
                return true;
            case R.id.export_installed_modules:
                Map<String, ModuleUtil.InstalledModule> installedModules = ModuleUtil.getInstance().getModules();

                if (installedModules.isEmpty()) {
                    Snackbar.make(findViewById(R.id.snackbar), R.string.no_installed_modules, Snackbar.LENGTH_SHORT).show();
                    return false;
                }
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/*");
                intent.putExtra(Intent.EXTRA_TITLE, "installed_modules.list");
                startActivityForResult(intent, 43);
                return true;
            case R.id.import_installed_modules:
            case R.id.import_enabled_modules:
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, 44);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void importModules(Uri uri) {
        RepoLoader repoLoader = RepoLoader.getInstance();
        List<Module> list = new ArrayList<>();

        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            InputStreamReader isr = new InputStreamReader(inputStream);
            BufferedReader br = new BufferedReader(isr);
            String line;
            while ((line = br.readLine()) != null) {
                Module m = repoLoader.getModule(line);

                if (m == null) {
                    Snackbar.make(findViewById(R.id.snackbar), getString(R.string.download_details_not_found, line), Snackbar.LENGTH_SHORT).show();
                } else {
                    list.add(m);
                }
            }
            br.close();
        } catch (Exception e) {
            e.printStackTrace();
            Snackbar.make(findViewById(R.id.snackbar), e.getLocalizedMessage(), Snackbar.LENGTH_SHORT).show();
        }

        for (final Module m : list) {
            if (mModuleUtil.getModule(m.packageName) != null) {
                continue;
            }
            ModuleVersion mv = null;
            for (int i = 0; i < m.versions.size(); i++) {
                ModuleVersion mvTemp = m.versions.get(i);

                if (mvTemp.relType == ReleaseType.STABLE) {
                    mv = mvTemp;
                    break;
                }
            }

            if (mv != null) {
                DownloadsUtil.addModule(this, m.name, mv.downloadLink, (context, info) -> new InstallApkUtil(this, info).execute());
            }
        }

        ModuleUtil.getInstance().reloadInstalledModules();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mModuleUtil.removeListener(this);
        mListView.setAdapter(null);
        mAdapter = null;
    }

    @Override
    public void onSingleInstalledModuleReloaded(ModuleUtil moduleUtil, String packageName, ModuleUtil.InstalledModule module) {
        mModuleUtil.updateModulesList(false);
        runOnUiThread(reloadModules);
    }

    @Override
    public void onInstalledModulesReloaded(ModuleUtil moduleUtil) {
        mModuleUtil.updateModulesList(false);
        runOnUiThread(reloadModules);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ModuleUtil.InstalledModule module = ModuleUtil.getInstance().getModule(selectedPackageName);
        if (module == null) {
            return false;
        }
        switch (item.getItemId()) {
            case R.id.menu_launch:
                String packageName = module.packageName;
                if (packageName == null) {
                    return false;
                }
                Intent launchIntent = getSettingsIntent(packageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Snackbar.make(findViewById(R.id.snackbar), R.string.module_no_ui, Snackbar.LENGTH_LONG).show();
                }
                return true;

            case R.id.menu_download_updates:
                Intent detailsIntent = new Intent(this, DownloadDetailsActivity.class);
                detailsIntent.setData(Uri.fromParts("package", module.packageName, null));
                startActivity(detailsIntent);
                return true;

            case R.id.menu_support:
                NavUtil.startURL(this, Uri.parse(RepoDb.getModuleSupport(module.packageName)));
                return true;

            case R.id.menu_app_store:
                Uri uri = Uri.parse("market://details?id=" + module.packageName);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                return true;

            case R.id.menu_app_info:
                startActivity(new Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", module.packageName, null)));
                return true;

            case R.id.menu_uninstall:
                startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", module.packageName, null)));
                return true;
        }
        return super.onContextItemSelected(item);
    }

    private Intent getSettingsIntent(String packageName) {
        // taken from
        // ApplicationPackageManager.getLaunchIntentForPackage(String)
        // first looks for an Xposed-specific category, falls back to
        // getLaunchIntentForPackage
        PackageManager pm = getPackageManager();

        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(SETTINGS_CATEGORY);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = pm.queryIntentActivities(intentToResolve, 0);

        if (ris.size() <= 0) {
            return pm.getLaunchIntentForPackage(packageName);
        }

        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(ris.get(0).activityInfo.packageName, ris.get(0).activityInfo.name);
        return intent;
    }

    private boolean lowercaseContains(String s, CharSequence filter) {
        return !TextUtils.isEmpty(s) && s.toLowerCase().contains(filter);
    }

    @Override
    public void onBackPressed() {
        if (mSearchView.isIconified()) {
            super.onBackPressed();
        } else {
            mSearchView.setIconified(true);
        }
    }

    private class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.ViewHolder> {
        Collection<ModuleUtil.InstalledModule> items;

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_module, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ModuleUtil.InstalledModule item = (ModuleUtil.InstalledModule) items.toArray()[position];
            holder.itemView.setOnClickListener(v -> {
                String packageName = item.packageName;
                if (packageName == null) {
                    return;
                }
                Intent launchIntent = getSettingsIntent(packageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Snackbar.make(findViewById(R.id.snackbar), R.string.module_no_ui, Snackbar.LENGTH_LONG).show();
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                selectedPackageName = item.packageName;
                return false;
            });

            holder.itemView.setOnCreateContextMenuListener((menu, v, menuInfo) -> {
                getMenuInflater().inflate(R.menu.context_menu_modules, menu);
                ModuleUtil.InstalledModule installedModule = ModuleUtil.getInstance().getModule(item.packageName);
                if (installedModule == null) {
                    return;
                }
                try {
                    String support = RepoDb.getModuleSupport(installedModule.packageName);
                    if (NavUtil.parseURL(support) == null) {
                        menu.removeItem(R.id.menu_support);
                    }
                } catch (RepoDb.RowNotFoundException e) {
                    menu.removeItem(R.id.menu_download_updates);
                    menu.removeItem(R.id.menu_support);
                }
            });
            holder.appName.setText(item.getAppName());

            TextView version = holder.appVersion;
            version.setText(Objects.requireNonNull(item).versionName);
            version.setSelected(true);

            TextView packageTv = holder.appPackage;
            packageTv.setText(item.packageName);

            String creationDate = dateformat.format(new Date(item.installTime));
            String updateDate = dateformat.format(new Date(item.updateTime));
            holder.timestamps.setText(getString(R.string.install_timestamps, creationDate, updateDate));

            holder.appIcon.setImageDrawable(item.getIcon());

            TextView descriptionText = holder.appDescription;
            descriptionText.setVisibility(View.VISIBLE);
            if (!item.getDescription().isEmpty()) {
                descriptionText.setText(item.getDescription());
            } else {
                descriptionText.setText(getString(R.string.module_empty_description));
                descriptionText.setTextColor(getResources().getColor(R.color.warning));
            }

            Switch mSwitch = holder.mSwitch;
            mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                String packageName = item.packageName;
                boolean changed = mModuleUtil.isModuleEnabled(packageName) ^ isChecked;
                if (changed) {
                    mModuleUtil.setModuleEnabled(packageName, isChecked);
                    mModuleUtil.updateModulesList(true);
                }
            });
            mSwitch.setChecked(mModuleUtil.isModuleEnabled(item.packageName));
            TextView warningText = holder.warningText;

            if (item.minVersion == 0) {
                if (!XposedApp.getPreferences().getBoolean("skip_xposedminversion_check", false)) {
                    mSwitch.setEnabled(false);
                }
                warningText.setText(getString(R.string.no_min_version_specified));
                warningText.setVisibility(View.VISIBLE);
            } else if (installedXposedVersion > 0 && item.minVersion > installedXposedVersion) {
                if (!XposedApp.getPreferences().getBoolean("skip_xposedminversion_check", false)) {
                    mSwitch.setEnabled(false);
                }
                warningText.setText(String.format(getString(R.string.warning_xposed_min_version), item.minVersion));
                warningText.setVisibility(View.VISIBLE);
            } else if (item.minVersion < ModuleUtil.MIN_MODULE_VERSION) {
                if (!XposedApp.getPreferences().getBoolean("skip_xposedminversion_check", false)) {
                    mSwitch.setEnabled(false);
                }
                warningText.setText(String.format(getString(R.string.warning_min_version_too_low), item.minVersion, ModuleUtil.MIN_MODULE_VERSION));
                warningText.setVisibility(View.VISIBLE);
            } else if (item.isInstalledOnExternalStorage()) {
                if (!XposedApp.getPreferences().getBoolean("skip_xposedminversion_check", false)) {
                    mSwitch.setEnabled(false);
                }
                warningText.setText(getString(R.string.warning_installed_on_external_storage));
                warningText.setVisibility(View.VISIBLE);
            } else if (installedXposedVersion == 0 || (installedXposedVersion == -1)) {
                if (!XposedApp.getPreferences().getBoolean("skip_xposedminversion_check", false)) {
                    mSwitch.setEnabled(false);
                }
                warningText.setText(getString(R.string.not_installed_no_lollipop));
                warningText.setVisibility(View.VISIBLE);
            } else {
                mSwitch.setEnabled(true);
                warningText.setVisibility(View.GONE);
            }
        }

        void addAll(Collection<ModuleUtil.InstalledModule> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            if (items != null) {
                return items.size();
            } else {
                return 0;
            }
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            TextView appName;
            TextView appPackage;
            TextView appDescription;
            TextView appVersion;
            TextView timestamps;
            TextView warningText;
            Switch mSwitch;

            ViewHolder(View itemView) {
                super(itemView);
                appIcon = itemView.findViewById(R.id.app_icon);
                appName = itemView.findViewById(R.id.app_name);
                appDescription = itemView.findViewById(R.id.description);
                appPackage = itemView.findViewById(R.id.package_name);
                appVersion = itemView.findViewById(R.id.version_name);
                timestamps = itemView.findViewById(R.id.timestamps);
                warningText = itemView.findViewById(R.id.warning);
                mSwitch = itemView.findViewById(R.id.checkbox);
            }
        }
    }

    class ApplicationFilter extends Filter {

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            runOnUiThread(reloadModules);
            return null;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            runOnUiThread(reloadModules);
        }
    }
}
