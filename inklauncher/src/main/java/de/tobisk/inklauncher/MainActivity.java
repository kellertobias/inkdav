package de.tobisk.inklauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_HOME_ROLE = 1001;
    private static final String[][] SHORTCUTS = {{"Calendar", "de.tobisk.inkdav"}, {"Tasks", "de.tobisk.inkdav.todos"}, {"Files", "de.tobisk.inkdav.files"}, {"Notes", "de.tobisk.inkvault"}, {"Browser", "org.chromium.chrome"}, {"Reader", "org.koreader.launcher"}, {"Audiobooks", "com.audiobookshelf.app"}};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.ENGLISH);
    private final SimpleDateFormat dateOnly = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
    private final SimpleDateFormat timeOnly = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private TextView clock, date;
    private LinearLayout events, notes;
    private boolean defaultPromptShown;
    private final Runnable tick = new Runnable() { public void run() { updateClock(); handler.postDelayed(this, 1000); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        clock = new TextView(this);
        date = new TextView(this);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        FrameLayout page = new FrameLayout(this); page.setBackgroundColor(Color.WHITE);
        View menuBar = new View(this); menuBar.setBackgroundColor(Color.BLACK); page.addView(menuBar, new FrameLayout.LayoutParams(-1, dp(38), Gravity.TOP));
        LinearLayout timeBlock = column(); timeBlock.setGravity(Gravity.CENTER_HORIZONTAL); timeBlock.setPadding(dp(48), dp(22), dp(48), 0);
        timeBlock.setGravity(Gravity.START); timeBlock.setPadding(dp(42), dp(22), dp(42), 0);
        clock.setTextColor(Color.BLACK); clock.setTextSize(96); clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL)); clock.setIncludeFontPadding(false); clock.setGravity(Gravity.START); timeBlock.addView(clock);
        date.setTextColor(Color.BLACK); date.setTextSize(32); date.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL)); date.setIncludeFontPadding(false); date.setGravity(Gravity.START); LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-1, -2); dateParams.topMargin = -dp(8); timeBlock.addView(date, dateParams);
        FrameLayout.LayoutParams timeParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); timeParams.topMargin = dp(118); page.addView(timeBlock, timeParams);
        LinearLayout shortcuts = column(); for (String[] shortcut : SHORTCUTS) { TextView b = button(shortcut[0]); b.setOnClickListener(v -> launch(shortcut[1])); shortcuts.addView(b); }
        TextView more = button("more..."); more.setTextColor(Color.GRAY); more.setOnClickListener(v -> showAllApps()); shortcuts.addView(more);
        FrameLayout.LayoutParams shortcutParams = new FrameLayout.LayoutParams(dp(480), -2, Gravity.START | Gravity.TOP); shortcutParams.leftMargin = dp(42); shortcutParams.topMargin = dp(300); page.addView(shortcuts, shortcutParams);
        LinearLayout bottom = new LinearLayout(this); bottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout notePanel = column(); notePanel.setPadding(0, 0, dp(28), 0); notePanel.addView(sectionHeader("Recent Notes"));
        HorizontalScrollView noteScroll = new HorizontalScrollView(this); noteScroll.setHorizontalScrollBarEnabled(false); notes = new LinearLayout(this); notes.setOrientation(LinearLayout.HORIZONTAL); noteScroll.addView(notes); LinearLayout.LayoutParams noteContentParams = new LinearLayout.LayoutParams(-1, 0, 1); noteContentParams.topMargin = dp(16); notePanel.addView(noteScroll, noteContentParams); bottom.addView(notePanel, new LinearLayout.LayoutParams(0, -1, 2f));
        LinearLayout eventPanel = column(); eventPanel.addView(sectionHeader("Next Appointments")); events = column(); LinearLayout.LayoutParams eventContentParams = new LinearLayout.LayoutParams(-1, 0, 1); eventContentParams.topMargin = dp(16); eventPanel.addView(events, eventContentParams); bottom.addView(eventPanel, new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, dp(470), Gravity.BOTTOM); bottomParams.setMargins(dp(42), 0, dp(42), 0); page.addView(bottom, bottomParams);
        setContentView(page);
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(tick); handler.post(tick); loadEvents(); loadNotes(); promptToBecomeDefaultIfNeeded(); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private void updateClock() { Date now = new Date(); clock.setText(clockFormat.format(now)); date.setText(dateFormat.format(now)); }
    private LinearLayout sectionHeader(String text) { LinearLayout header = column(); TextView title = new TextView(this); title.setText(text); title.setTextSize(24); title.setTextColor(Color.BLACK); title.setPadding(0, 0, 0, 0); header.addView(title); View line = new View(this); line.setBackgroundColor(Color.DKGRAY); LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1)); lineParams.topMargin = dp(2); header.addView(line, lineParams); return header; }
    private TextView button(String text) { TextView v = new TextView(this); v.setText(text); v.setTextColor(Color.BLACK); v.setTextSize(27); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(12), 0, dp(12), 0); v.setMinHeight(dp(55)); v.setClickable(true); v.setBackgroundResource(android.R.drawable.list_selector_background); return v; }
    private TextView detail(String text) { TextView v = new TextView(this); v.setText(text); v.setTextColor(Color.DKGRAY); v.setTextSize(16); v.setPadding(dp(14), dp(8), dp(14), dp(12)); return v; }
    private void loadEvents() {
        events.removeAllViews();
        try (Cursor c = getContentResolver().query(android.net.Uri.parse("content://de.tobisk.inkdav.launcher/events"), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) { events.addView(detail("No events within next 48 h")); return; }
            do { long start = c.getLong(c.getColumnIndexOrThrow("start")), end = c.getLong(c.getColumnIndexOrThrow("end")); boolean allDay = c.getInt(c.getColumnIndexOrThrow("allDay")) != 0; boolean multiDay = end - start > 24L * 60 * 60 * 1000; String startDate = dateOnly.format(new Date(start)), endDate = dateOnly.format(new Date(end)); String when = allDay ? startDate + (multiDay ? " – " + endDate : "") + " · All day" : startDate + "  " + timeOnly.format(new Date(start)) + " – " + (multiDay || !startDate.equals(endDate) ? endDate + "  " : "") + timeOnly.format(new Date(end)); TextView item = detail(c.getString(c.getColumnIndexOrThrow("title")) + "\n" + c.getString(c.getColumnIndexOrThrow("calendar")) + "\n" + when); item.setOnClickListener(v -> launch("de.tobisk.inkdav")); View bar = new View(this); bar.setBackgroundColor((int)c.getLong(c.getColumnIndexOrThrow("color"))); LinearLayout row = new LinearLayout(this); row.setPadding(0, 0, 0, dp(4)); row.addView(bar, new LinearLayout.LayoutParams(dp(5), -1)); row.addView(item, new LinearLayout.LayoutParams(0, -2, 1)); events.addView(row); } while (c.moveToNext());
        } catch (Exception ignored) { events.addView(detail("No events within next 48 h")); }
    }
    private void loadNotes() {
        notes.removeAllViews();
        try (Cursor c = getContentResolver().query(android.net.Uri.parse("content://de.tobisk.inkvault.launcher/notes"), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) { notes.addView(detail("No recently edited notes")); return; }
            do { LinearLayout card = column(); card.setPadding(0, 0, dp(10), 0); String path = c.getString(c.getColumnIndexOrThrow("path")); byte[] image = c.getBlob(c.getColumnIndexOrThrow("thumbnail")); FrameLayout frameBox = new FrameLayout(this); GradientDrawable frame = new GradientDrawable(); frame.setColor(0xfff3f3f3); frame.setStroke(dp(3), 0xff808080); frameBox.setBackground(frame); ImageView preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.CENTER_CROP); if (image != null) { Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length); preview.setImageBitmap(bitmap); } FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(-1, -1); previewParams.setMargins(dp(3), dp(3), dp(3), dp(3)); frameBox.addView(preview, previewParams); card.addView(frameBox, new LinearLayout.LayoutParams(dp(155), dp(219))); String title = c.getString(c.getColumnIndexOrThrow("title")); long modified = c.getLong(c.getColumnIndexOrThrow("modified")); TextView name = detail(title); name.setTextColor(Color.BLACK); name.setPadding(dp(8), dp(4), dp(8), 0); card.addView(name, new LinearLayout.LayoutParams(dp(155), -2)); TextView edited = detail("Edited " + dateOnly.format(new Date(modified))); edited.setTextColor(Color.GRAY); edited.setPadding(dp(8), 0, dp(8), dp(4)); card.addView(edited, new LinearLayout.LayoutParams(dp(155), -2)); card.setOnClickListener(v -> openNote(path)); notes.addView(card); } while (c.moveToNext());
        } catch (Exception ignored) { notes.addView(detail("No recently edited notes")); }
    }
    private void launch(String target) { Intent intent = findByPackage(target); if (intent != null) startActivity(intent); }
    private void openNote(String path) { Intent intent = findByPackage("de.tobisk.inkvault"); if (intent != null) startActivity(intent.putExtra("de.tobisk.inkvault.OPEN_NOTE_PATH", path).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
    private void promptToBecomeDefaultIfNeeded() {
        if (defaultPromptShown || isDefaultLauncher()) return;
        defaultPromptShown = true;
        new AlertDialog.Builder(this).setTitle("Make Ink Launcher your Home app?").setMessage("Ink Launcher is not currently the default Home app.").setNegativeButton("Not now", null).setPositiveButton("Make default", (dialog, which) -> requestHomeRole()).show();
    }
    private boolean isDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            return roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_HOME);
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && getPackageName().equals(resolved.activityInfo.packageName);
    }
    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE);
                return;
            }
        }
        startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
    }
    private Intent findByPackage(String name) { for (ResolveInfo i : launchableApps()) if (name.equals(i.activityInfo.packageName)) return activityIntent(i); return getPackageManager().getLaunchIntentForPackage(name); }
    private Intent findByLabel(String label) { for (ResolveInfo i : launchableApps()) if (label.contentEquals(i.loadLabel(getPackageManager()))) return activityIntent(i); return null; }
    private Intent activityIntent(ResolveInfo i) { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(i.activityInfo.packageName, i.activityInfo.name); }
    private List<ResolveInfo> launchableApps() { Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER); List<ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(q, PackageManager.MATCH_ALL)); Collections.sort(apps, Comparator.comparing(i -> i.loadLabel(getPackageManager()).toString(), String.CASE_INSENSITIVE_ORDER)); return apps; }
    private void showAllApps() {
        List<ResolveInfo> apps = launchableApps();
        GridView grid = new GridView(this); grid.setNumColumns(4); grid.setVerticalSpacing(dp(18)); grid.setHorizontalSpacing(dp(12)); grid.setPadding(dp(28), dp(24), dp(28), dp(24));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int position) { return apps.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                ResolveInfo app = apps.get(position);
                LinearLayout item = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(MainActivity.this);
                item.removeAllViews(); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(dp(6), dp(8), dp(6), dp(8));
                ImageView icon = new ImageView(MainActivity.this); icon.setImageDrawable(app.loadIcon(getPackageManager())); item.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
                TextView label = new TextView(MainActivity.this); label.setText(app.loadLabel(getPackageManager())); label.setTextSize(13); label.setTextColor(Color.BLACK); label.setGravity(Gravity.CENTER); label.setMaxLines(2); item.addView(label, new LinearLayout.LayoutParams(-1, -2));
                return item;
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> startActivity(activityIntent(apps.get(position))));
        new AlertDialog.Builder(this).setTitle("All apps").setView(grid).show();
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
