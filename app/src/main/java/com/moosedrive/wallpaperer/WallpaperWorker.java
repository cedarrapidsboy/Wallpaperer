package com.moosedrive.wallpaperer;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * The type Wallpaper worker.
 */
public class WallpaperWorker extends Worker {

    private final Context context;
    private final ImageStore store;
    private ImageObject imgObject;


    /**
     * Instantiates a new Wallpaper worker.
     * Valid input data:
     * "id" - an ImageObject id
     *
     * @param context      the context
     * @param workerParams the worker params
     */
    public WallpaperWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        String imgId = workerParams.getInputData().getString("id");
        store = ImageStore.getInstance();
        if (store.size() == 0)
            store.updateFromPrefs(context);
        if (imgId != null) {
            imgObject = store.getImageObject(imgId);
        } else {
            imgObject = null;
        }
    }

    @NonNull
    @Override
    public Result doWork() {

        int compatWidth;
        int compatHeight;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowMetrics metrics = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getCurrentWindowMetrics();
            compatWidth = metrics.getBounds().width();
            compatHeight = metrics.getBounds().height();
        } else {
            compatHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
            compatWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        }
        int height = compatHeight; //(wm.getDesiredMinimumHeight() > 0) ? wm.getDesiredMinimumHeight() : compatHeight;
        int width = compatWidth; //(wm.getDesiredMinimumWidth() > 0) ? wm.getDesiredMinimumWidth() : compatWidth;

        try {
            Uri imgUri = null;
            if (imgObject != null) {
                if (store.getPosition(imgObject.getId()) == -1 && store.getLastWallpaperPos() >= store.size())
                    imgObject = store.getImageObject(0);
                imgUri = imgObject.getUri();
            } else {
                if (store.size() > 0) {
                    Random rand = new Random();
                    int nextInt = rand.nextInt(store.size());
                    imgObject = store.getImageObject(nextInt);
                    imgUri = imgObject.getUri();
                }
            }
            if (imgUri != null) {
                try {
                    ParcelFileDescriptor pfd = context.
                            getContentResolver().
                            openFileDescriptor(imgUri, "r");
                    final Bitmap bitmapSource = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                    pfd.close();
                    new Thread(() -> {
                        try {
                            boolean crop = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.preference_image_crop), true);
                            Bitmap bitmap = StorageUtils.resizeBitmapCenter(width, height, bitmapSource, crop);
                            WallpaperManager.getInstance(context).setBitmap(bitmap);
                            store.setLastWallpaperId(imgObject.getId());
                            SharedPreferences.Editor prefEdit = PreferenceManager.getDefaultSharedPreferences(context).edit();
                            long now = new Date().getTime();
                            prefEdit.putLong(context.getString(R.string.preference_worker_last_change), now);
                            prefEdit.putString(context.getString(R.string.last_wallpaper), imgObject.getId());
                            prefEdit.apply();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }).start();
                } catch (IOException e) {
                    store.delImageObject(imgObject.getId());
                    //StorageUtils.releasePersistableUriPermission(context, imgObject.getUri());
                    store.saveToPrefs(context);
                    e.printStackTrace();
                }
            }
        } catch (CancellationException e) {
            //do nothing
        }
        // schedule the next wallpaper change
        if (PreferenceHelper.isActive(context)) {
            //Get image at current position -- because the active image may have been swiped away
            //   and we'd want to show the image that fell into its place
            ImageObject nextImage = store.getImageObject(store.getLastWallpaperPos());
            //If image at current position is the same as the last displayed image,
            //   move to the next position
            if (nextImage != null && nextImage.getId().equals(store.getLastWallpaperId()))
                nextImage = store.getImageObject(store.getLastWallpaperPos()+1);
            //If the image at the desired position doesn't exist, return to the beginning
            if (nextImage == null)
                nextImage = store.getImageObject(0);
            scheduleRandomWallpaper(context, false, nextImage.getId());
        }
        return Result.success();
    }

    /**
     * Start the wallpaper scheduler.
     * Will wait the preferred wallpaper delay (from preferences) prior to the first execution.
     *
     * @param context the context
     */
    public static void scheduleRandomWallpaper(Context context) {
        scheduleRandomWallpaper(context, false, null);
    }

    /**
     * Change the wallpaper now.
     * Will reset the wallpaper scheduler timer if it is currently running.
     *
     * @param context     the context
     * @param imgObjectId the img object id
     */
    public static void changeWallpaperNow(Context context, String imgObjectId) {
        scheduleRandomWallpaper(context, true, imgObjectId);
    }

    /**
     * Schedule random wallpaper.
     * Depending on arguments it may change the wallpaper immediately or simply start the scheduler new.
     *
     * @param context     the context
     * @param runNow      change the wallpaper immediately, will neither cancel nor start a schedule (will reset a running schedule)
     * @param imgObjectId the img object id
     */
    private static void scheduleRandomWallpaper(Context context, Boolean runNow, String imgObjectId) {
        Context mContext = context.getApplicationContext();
        boolean bReqIdle = PreferenceHelper.idleOnly(mContext);
        Data.Builder data = new Data.Builder();
        if (imgObjectId != null) {
            data.putString("id", imgObjectId);
        }
        OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest
                .Builder(WallpaperWorker.class)
                .setInputData(data.build())
                .setConstraints(new Constraints.Builder()
                        .setRequiresDeviceIdle(bReqIdle)
                        .setRequiresBatteryNotLow(true)
                        .build());
        if (!bReqIdle)
            requestBuilder.setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        if (!runNow) {
            requestBuilder.setInitialDelay(PreferenceHelper.getWallpaperDelay(mContext), TimeUnit.MILLISECONDS)
                    .addTag(context.getString(R.string.work_random_wallpaper_id));
            WorkManager.getInstance(context).cancelAllWorkByTag(context.getString(R.string.work_random_wallpaper_id));
        }
        OneTimeWorkRequest saveRequest = requestBuilder.build();
        WorkManager.getInstance(mContext)
                .enqueue(saveRequest);

        if (!runNow) {
            SharedPreferences.Editor prefEdit = PreferenceManager.getDefaultSharedPreferences(mContext).edit();
            prefEdit.putLong(mContext.getString(R.string.preference_worker_last_queue), new Date().getTime());
            prefEdit.apply();
        }
    }
}
