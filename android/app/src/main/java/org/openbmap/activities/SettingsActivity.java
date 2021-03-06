/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.activities;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.json.JSONObject;
import org.openbmap.Preferences;
import org.openbmap.R;
import org.openbmap.utils.FileUtils;
import org.openbmap.utils.MapUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Preferences activity.
 */
public class SettingsActivity extends PreferenceActivity {

    private static final String TAG = SettingsActivity.class.getSimpleName();

    private DownloadManager mDownloadManager;

    /*
     * Id of the active catalog download or -1 if no active download
     */
    private long mCurrentCatalogDownloadId = -1;

    /*
     * Id of the active map download or -1 if no active download
     */
    private long mCurrentMapDownloadId = -1;

    private BroadcastReceiver mReceiver = null;

    @SuppressLint("NewApi")
    @Override
    protected final void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preferences);

        // with versions >= GINGERBREAD use download manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            initDownloadManager();
        }

        initCatalogDownloadControl();
        initActiveCatalogControl();

        initActiveMapControl();

        initGpsLogIntervalControl();

        initAdvancedSettingsButton();

        DialogPreferenceMaps maps = (DialogPreferenceMaps) findPreference(Preferences.KEY_MAPS_DIALOG);
    }

    /**
     * Initialises download manager for GINGERBREAD and newer
     */
    private void initDownloadManager() {
        mDownloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        mReceiver = new BroadcastReceiver() {
            @SuppressLint("NewApi")
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final String action = intent.getAction();
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                    final long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                    final Query query = new Query();
                    query.setFilterById(downloadId);
                    final Cursor c = mDownloadManager.query(query);
                    if (c.moveToFirst()) {
                        final int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        final int reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                        Log.i(TAG, "Download status:" + c.getInt(statusCol));
                        if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(statusCol)) {
                            // we're not checking download id here, that is done in handleDownloads
                            final String uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                            handleDownloads(uriString);
                        } else if (DownloadManager.STATUS_FAILED == c.getInt(statusCol)) {
                            Log.e(TAG, "Download failed:" + c.getString(reasonCol));
                        }
                    }
                }
            }
        };

        registerReceiver(mReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    @Override
    protected final void onDestroy() {
        try {
            if (mReceiver != null) {
                Log.i(TAG, "Unregistering broadcast receivers");
                unregisterReceiver(mReceiver);
            }
        } catch (final IllegalArgumentException e) {
            // do nothing here {@see http://stackoverflow.com/questions/2682043/how-to-check-if-receiver-is-registered-in-android}
            super.onDestroy();
            return;
        }

        super.onDestroy();
    }

    /**
     * Starts advanced setting activity
     */
    private void initAdvancedSettingsButton() {
        final Preference pref = findPreference(Preferences.KEY_ADVANCED_SETTINGS);
        pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference preference) {
                startActivity(new Intent(pref.getContext(), AdvancedSettingsActivity.class));
                return true;
            }
        });
    }

    /**
     * Initializes gps logging interval.
     */
    private void initGpsLogIntervalControl() {
        // Update GPS logging interval summary to the current value
        final Preference pref = findPreference(org.openbmap.Preferences.KEY_GPS_LOGGING_INTERVAL);
        pref.setSummary(
                PreferenceManager.getDefaultSharedPreferences(this).getString(org.openbmap.Preferences.KEY_GPS_LOGGING_INTERVAL, org.openbmap.Preferences.VAL_GPS_LOGGING_INTERVAL)
                        + " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
                        + ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
        pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference preference, final Object newValue) {
                // Set summary with the interval and "seconds"
                preference.setSummary(newValue
                        + " " + getResources().getString(R.string.prefs_gps_logging_interval_seconds)
                        + ". " + getResources().getString(R.string.prefs_gps_logging_interval_summary));
                return true;
            }
        });
    }

    /**
     * Populates the active map list preference by scanning available map files.
     */
    private void initActiveMapControl() {
        String[] entries;
        String[] values;

        // Check for presence of maps directory
        final File folder = MapUtils.getMapFolder(SettingsActivity.this);
        Log.d(TAG, "Listing maps in " + folder.getAbsolutePath());

        // List each map file
        if (folder.exists() && folder.canRead()) {
            final String[] mapFiles = folder.list(new FilenameFilter() {
                @Override
                public boolean accept(final File dir, final String filename) {
                    return filename.endsWith(org.openbmap.Preferences.MAP_FILE_EXTENSION);
                }
            });

            // Create array of values for each map file + one for not selected
            entries = new String[mapFiles.length + 1];
            values = new String[mapFiles.length + 1];

            // Create default / none entry
            entries[0] = getResources().getString(R.string.prefs_map_none);
            values[0] = org.openbmap.Preferences.VAL_MAP_NONE;

            for (int i = 0; i < mapFiles.length; i++) {
                entries[i + 1] = mapFiles[i].substring(0, mapFiles[i].length() - org.openbmap.Preferences.MAP_FILE_EXTENSION.length());
                values[i + 1] = mapFiles[i];
            }
        } else {
            // No map found, populate values with just the default entry.
            entries = new String[]{getResources().getString(R.string.prefs_map_none)};
            values = new String[]{org.openbmap.Preferences.VAL_MAP_NONE};
        }

        Log.d(TAG, "Found " + entries.length + " files");

        final ListPreference lf = (ListPreference) findPreference(Preferences.KEY_MAP_FILE);
        lf.setEntries(entries);
        lf.setEntryValues(values);
    }

    /**
     * Initializes wifi catalog source preference
     */
    private void initCatalogDownloadControl() {
        final Preference pref = findPreference(Preferences.KEY_DOWNLOAD_WIFI_CATALOG);
        pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference preference) {
                if (!getLocalCatalogVersion().equals(Preferences.CATALOG_VERSION_NONE) && !isNewerCatalogAvailable(getLocalCatalogVersion())) {
                    Log.d(TAG, "Latest version already installed" + getLocalCatalogVersion());
                    Toast.makeText(SettingsActivity.this, getString(R.string.wifi_catalog_up_to_date), Toast.LENGTH_LONG).show();
                    return true;
                } else {
                    Log.v(TAG, "Local version " + getLocalCatalogVersion() + ", downloading newer version");
                }

                if (mCurrentCatalogDownloadId > -1  ) {
                    Toast.makeText(SettingsActivity.this, getString(R.string.other_download_active), Toast.LENGTH_LONG).show();
                    return true;
                }

                // try to create directory
                final File folder = getCatalogFolder();

                boolean folderAccessible = false;
                if (folder.exists() && folder.canWrite()) {
                    Log.i(TAG, "Folder writable " + folder);
                    folderAccessible = true;
                }

                if (!folder.exists()) {
                    Log.i(TAG, "Creating folder" + folder);
                    folderAccessible = folder.mkdirs();
                }
                if (folderAccessible) {
                    setLocalCatalogVersion(Preferences.CATALOG_VERSION_NONE);

                    final File target = new File(folder.getAbsolutePath() + File.separator + Preferences.CATALOG_FILE);
                    if (target.exists()) {
                        Log.i(TAG, "Catalog already exists. Overwriting..");
                        target.delete();
                    }

                    try {
                        // try to download to target. If target isn't below Environment.getExternalStorageDirectory(),
                        // e.g. on second SD card a security exception is thrown
                        Log.i(TAG, "Downloading " + Preferences.CATALOG_DOWNLOAD_URL);
                        final Request request = new Request(Uri.parse(Preferences.CATALOG_DOWNLOAD_URL));
                        request.setDestinationUri(Uri.fromFile(target));
                        mCurrentCatalogDownloadId = mDownloadManager.enqueue(request);
                    } catch (final SecurityException sec) {
                        Log.e(TAG, "Security exception: " + sec.getMessage());
                        mCurrentCatalogDownloadId = -1;
                        // download to temp dir and try to move to target later
                        Log.w(TAG, "Security exception, can't write to " + target + ", using " + SettingsActivity.this.getExternalCacheDir()
                                + File.separator + Preferences.CATALOG_FILE);
                        final File tempFile = new File(SettingsActivity.this.getExternalCacheDir() + File.separator + Preferences.CATALOG_FILE);
                        final Request request = new Request(Uri.parse(Preferences.CATALOG_DOWNLOAD_URL));
                        request.setDestinationUri(Uri.fromFile(tempFile));
                        mDownloadManager.enqueue(request);
                    }
                } else {
                    mCurrentCatalogDownloadId = -1;
                    Toast.makeText(preference.getContext(), R.string.error_save_file_failed, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
    }

    private String getServerCatalogVersion(){
        Log.i(TAG, "Checking server version");
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        DefaultHttpClient httpclient = new DefaultHttpClient(new BasicHttpParams());
        HttpPost httppost = new HttpPost(Preferences.CATALOG_VERSION_URL);
        httppost.setHeader("Content-type", "application/json");

        InputStream inputStream = null;
        String result = null;
        try {
            HttpResponse response = httpclient.execute(httppost);
            HttpEntity entity = response.getEntity();

            inputStream = entity.getContent();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"), 8);
            StringBuilder sb = new StringBuilder();

            String line = null;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line + "\n");
            }
            JSONObject jObj = new JSONObject(sb.toString());
            return jObj.getString("version");
        } catch (Exception e) {
            Log.e(TAG, "Error getting server version");
        } finally {
            try{
                if (inputStream != null) {inputStream.close();}
            } catch (Exception squish) {
                return Preferences.SERVER_CATALOG_VERSION_NONE;
            }
        }
        return Preferences.SERVER_CATALOG_VERSION_NONE;
    }

    /**
     * Compares local and server wifi catalog versions
     * @param local
     * @return true, if server has a newer version
     */
    private boolean isNewerCatalogAvailable(String local){
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Date currentDate = sdf.parse(local);
            Date serverVersion = sdf.parse(getServerCatalogVersion());
            if (serverVersion != null && currentDate != null && serverVersion.after(currentDate)) {
                Log.i(TAG, String.format("Local version %s: Newer version available %s", local, serverVersion));
                return  true;
            } else {
                Log.d(TAG, String.format(getString(R.string.wifi_catalog_up_to_date), local));
                return false;
            }
        } catch  (ParseException e) {
            Log.e(TAG, "Error parsing version");
            return false;
        }
    }

    /**
     * Gets a file reference for wifi catalog folder
     * @return map folder
     */
    @NonNull
    private File getCatalogFolder() {
        return new File(PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this).getString(
                Preferences.KEY_WIFI_CATALOG_FOLDER,
                SettingsActivity.this.getExternalFilesDir(null) + File.separator + Preferences.CATALOG_SUBDIR));
    }

    /**
     * Checks the local wifi catalog version
     * @return version number
     */
    private String getLocalCatalogVersion() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString(Preferences.KEY_CATALOG_VERSION, Preferences.CATALOG_VERSION_NONE);
    }

    private void setLocalCatalogVersion(String version) {
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString(Preferences.KEY_CATALOG_VERSION, version).commit();
    }

    /**
     * Populates the wifi catalog list preference by scanning catalog folder.
     */
    private void initActiveCatalogControl() {
        String[] entries;
        String[] values;

        // Check for presence of database directory
        final File folder = MapUtils.getCatalogFolder(SettingsActivity.this);
        Log.d(TAG, "Listing catalogs in " + folder.getAbsolutePath());

        if (folder.exists() && folder.canRead()) {
            // List each map file
            final String[] dbFiles = folder.list(new FilenameFilter() {
                @Override
                public boolean accept(final File dir, final String filename) {
                    return filename.endsWith(
                            org.openbmap.Preferences.CATALOG_FILE_EXTENSION);
                }
            });

            // Create array of values for each map file + one for not selected
            entries = new String[dbFiles.length + 1];
            values = new String[dbFiles.length + 1];

            // Create default / none entry
            entries[0] = getResources().getString(R.string.prefs_map_none);
            values[0] = org.openbmap.Preferences.VAL_CATALOG_NONE;

            for (int i = 0; i < dbFiles.length; i++) {
                entries[i + 1] = dbFiles[i].substring(0, dbFiles[i].length() - org.openbmap.Preferences.CATALOG_FILE_EXTENSION.length());
                values[i + 1] = dbFiles[i];
            }
        } else {
            // No wifi catalog found, populate values with just the default entry.
            entries = new String[]{getResources().getString(R.string.prefs_map_none)};
            values = new String[]{org.openbmap.Preferences.VAL_CATALOG_NONE};
        }

        Log.d(TAG, "Found " + entries.length + " files");
        final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_CATALOG_FILE);
        lf.setEntries(entries);
        lf.setEntryValues(values);
    }

    /**
     * Changes catalog preference item to given filename.
     * Helper method to activate wifi catalog following successful download
     *
     * @param absoluteFile absolute filename (including path)
     */
    private void activateWifiCatalog(final String absoluteFile) {
        final ListPreference lf = (ListPreference) findPreference(org.openbmap.Preferences.KEY_CATALOG_FILE);

        // get filename
        final String[] filenameArray = absoluteFile.split("\\/");
        final String file = filenameArray[filenameArray.length - 1];

        final CharSequence[] values = lf.getEntryValues();
        for (int i = 0; i < values.length; i++) {
            if (file.equals(values[i].toString())) {
                lf.setValueIndex(i);
            }
        }
    }

    /**
     * Selects downloaded file either as wifi catalog / active map (based on file extension).
     * @param file
     */
    public final void handleDownloads(String file) {
        Log.i(TAG, "Download completed " + file);
        // get current file extension
        final String[] filenameArray = file.split("\\.");
        final String extension = "." + filenameArray[filenameArray.length - 1];

        // replace prefix file:// in filename string
        file = file.replace("file://", "");

        if (extension.equals(org.openbmap.Preferences.CATALOG_FILE_EXTENSION)) {
            mCurrentCatalogDownloadId = -1;
            if (file.indexOf(SettingsActivity.this.getExternalCacheDir().getPath()) > -1) {
                Log.i(TAG, "Moving file to " + getCatalogFolder().getAbsolutePath());
                file = moveToFolder(file, getCatalogFolder().getAbsolutePath());
            }

            String version = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            setLocalCatalogVersion(version);

            initActiveCatalogControl();
            // handling wifi catalog files
            activateWifiCatalog(file);
        }
    }

    /**
     * Moves file to specified folder
     * @param file
     * @param folder
     * @return new file name
     */
    private String moveToFolder(final String file, final String folder) {
        // file path contains external cache dir, so we have to move..
        final File source = new File(file);
        final File destination = new File(folder + File.separator + source.getName());
        Log.i(TAG, file + " stored in temp folder. Moving to " + destination.getAbsolutePath());

        try {
            FileUtils.moveFile(source, destination);
        } catch (final IOException e) {
            Log.e(TAG, "I/O error while moving file");
        }
        return destination.getAbsolutePath();
    }
}
