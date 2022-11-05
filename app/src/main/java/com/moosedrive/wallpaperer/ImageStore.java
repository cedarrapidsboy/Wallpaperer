package com.moosedrive.wallpaperer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * The type Image store.
 */
public class ImageStore {
    public static final long MINIMUM_REQUIRED_FREE_SPACE = 734003200L;
    public static final int SORT_BY_ADDED = -1;
    public static final int SORT_BY_NAME = 0;
    public static final int SORT_BY_DATE = 1;
    public static final int SORT_BY_SIZE = 2;
    public static final int SORT_BY_CUSTOM = 3;
    public static final int SORT_DEFAULT = SORT_BY_ADDED;
    private static ImageStore store = null;
    private final Set<WallpaperAddedListener> wallpaperListeners = new HashSet<>();
    private final Set<ImageStoreSortListener> sortListeners = new HashSet<>();
    private final ArrayList<Collection<ImageObject>> sortedImages = new ArrayList<>();
    public CountDownLatch loadingDoneSignal;
    public HashSet<String> loadingErrors = new HashSet<>();
    private int sortCriteria = SORT_BY_ADDED;
    private LinkedHashMap<String, ImageObject> referenceImages;
    private String lastWallpaperId = "";
    private int lastWallpaperPos = -1;

    private ImageStore() {
        this.referenceImages = new LinkedHashMap<>();
        //SORT_BY_NAME==0
        sortedImages.add(new TreeSet<>(Comparator.comparing(ImageObject::getName)
                .thenComparing(ImageObject::getDate)
                .thenComparingLong(ImageObject::getSize)
                .thenComparing(ImageObject::getId)));
        //SORT_BY_DATE==1
        sortedImages.add(new TreeSet<>(Comparator.comparing(ImageObject::getDate)
                .thenComparing(ImageObject::getName)
                .thenComparingLong(ImageObject::getSize)
                .thenComparing(ImageObject::getId)));
        //SORT_BY_SIZE==2
        sortedImages.add(new TreeSet<>(Comparator.comparingLong(ImageObject::getSize)
                .thenComparing(ImageObject::getName)
                .thenComparing(ImageObject::getDate)
                .thenComparing(ImageObject::getId)));
        //SORT_BY_CUSTOM==3
        sortedImages.add(new LinkedList<>());
    }

    /**
     * Gets instance.
     *
     * @return the instance
     */
    public static synchronized ImageStore getInstance() {
        if (store == null) {
            store = new ImageStore();
        }
        return store;
    }

    public String getLastWallpaperId() {
        return lastWallpaperId;
    }

    public int getLastWallpaperPos() {
        return lastWallpaperPos;
    }

    public void setLastWallpaperPos(int lastPos) {
        this.lastWallpaperPos = lastPos;
    }

    public void setLastWallpaperId(String lastWallpaperId, boolean force) {
        this.lastWallpaperId = lastWallpaperId;
        if (force || !lastWallpaperId.equals(""))
            this.lastWallpaperPos = getPosition(lastWallpaperId);
    }

    public void shuffleImages(){
        Collections.shuffle((LinkedList<?>)sortedImages.get(SORT_BY_CUSTOM));
    }

    /**
     * Save to prefs.
     *
     * @param context the context
     */
    public synchronized void saveToPrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        JSONArray imageArray = new JSONArray();
        for (ImageObject io : referenceImages.values().toArray(new ImageObject[0])) {
            try {
                JSONObject imageJson = new JSONObject();
                imageJson.put("uri", io.getUri().toString());
                imageJson.put("id", io.getId());
                imageJson.put("name", io.getName());
                imageJson.put("size", io.getSize());
                imageJson.put("type", io.getType());
                imageJson.put("date", io.getDate().getTime());
                imageJson.put("color", io.getColor());
                imageArray.put(imageJson);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        edit.putString("sources", imageArray.toString());
        JSONArray customIds = new JSONArray();
        for (ImageObject io : sortedImages.get(SORT_BY_CUSTOM).toArray(new ImageObject[0]))
            customIds.put(io.getId());
        edit.putString("custom", customIds.toString());
        edit.putInt("sort",getSortCriteria());
        edit.putString(context.getString(R.string.last_wallpaper), lastWallpaperId);
        edit.putInt(context.getString(R.string.last_wallpaper_pos), lastWallpaperPos);
        edit.apply();
    }

    /**
     * Load from prefs image store. This will clear the current ImageStore.
     *
     * @param context the context
     */
    public synchronized void updateFromPrefs(Context context) {
        ImageStore is = store;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            JSONArray imageArray = new JSONArray(prefs.getString("sources", "[]"));
            for (int i = 0; i < imageArray.length(); i++) {
                ParcelFileDescriptor pfd;
                Uri uri = Uri.parse(imageArray.getJSONObject(i).getString("uri"));
                try {
                    pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                    pfd.close();
                    ImageObject io = is.addImageObject(uri,
                            imageArray.getJSONObject(i).getString("id"),
                            imageArray.getJSONObject(i).getString("name"),
                            imageArray.getJSONObject(i).getLong("size"),
                            imageArray.getJSONObject(i).getString("type"),
                            new Date(imageArray.getJSONObject(i).getLong("date")));
                    io.setColor(imageArray.getJSONObject(i).getInt("color"));
                } catch (FileNotFoundException e) {
                    //StorageUtils.releasePersistableUriPermission(context, uri);
                    System.out.println("ERROR: loadFromPrefs: File no longer exists.");
                    e.printStackTrace();
                } catch (NoSuchAlgorithmException | IOException | JSONException e) {
                    e.printStackTrace();
                }
            }
            JSONArray customIds = new JSONArray(prefs.getString("custom","[]"));
            LinkedList<ImageObject> ll = (LinkedList<ImageObject>)sortedImages.get(SORT_BY_CUSTOM);
            ll.clear();
            for (int i = 0; i < customIds.length(); i++){
                ImageObject obj = referenceImages.get(customIds.getString(i));
                if (obj != null && !ll.contains(obj))
                    ll.add(referenceImages.get(customIds.getString(i)));
            }
            for (ImageObject obj : referenceImages.values())
                if (!ll.contains(obj))
                    ll.add(obj);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        store.lastWallpaperId = prefs.getString(context.getString(R.string.last_wallpaper), "");
        store.lastWallpaperPos = prefs.getInt(context.getString(R.string.last_wallpaper_pos), -1);
        store.setSortCriteria(prefs.getInt("sort",SORT_DEFAULT));
    }

    /**
     * Add image object.
     *
     * @param uri      the uri
     * @param filename the filename
     * @param size     the size
     * @param type     the type
     * @param date     the date
     * @throws NoSuchAlgorithmException the no such algorithm exception
     * @throws IOException              the io exception
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized ImageObject addImageObject(Uri uri, String id, String filename, long size, String type, Date date) throws NoSuchAlgorithmException, IOException {
        ImageObject img = new ImageObject(uri, id, filename, size, type, date);
        addImageObject(img);
        return img;
    }

    /**
     * Add image object.
     *
     * @param img the img
     */
    public synchronized void addImageObject(ImageObject img) {
        addImageObject(img, -1, -1);
    }

    /**
     * Add image object.
     *
     * @param imgTry      the img
     * @param refPosition the position Where to place the new object, or -1 to append
     */
    public synchronized void addImageObject(ImageObject imgTry, int refPosition, int customPosition) {
        ImageObject img = imgTry;
        if (referenceImages.size() == 0 || refPosition < 0 || refPosition > (referenceImages.size() - 1)) {
            img = referenceImages.put(img.getId(), img);
        } else {
            int curPos = 0;
            LinkedHashMap<String, ImageObject> oldImages = referenceImages;
            LinkedHashMap<String, ImageObject> newImages = new LinkedHashMap<>();
            for (String key : oldImages.keySet()) {
                if (curPos == refPosition)
                    img = newImages.put(imgTry.getId(), imgTry);
                if (!key.equals(imgTry.getId()))
                    newImages.put(key, oldImages.get(key));
                curPos++;
            }
            referenceImages = newImages;
        }
        //Add the image to each sorted list
        if (img == null)
            img = imgTry;
        for (Collection<ImageObject> imgArray : sortedImages){
            if (imgArray instanceof Set)
                imgArray.add(img);
            else if (imgArray instanceof LinkedList) {
                if (!imgArray.contains(img)) {
                    if (customPosition < 0 || customPosition > (referenceImages.size() - 1))
                        imgArray.add(img);
                    else
                        ((LinkedList<ImageObject>)imgArray).add(customPosition,img);
                }
            }

        }
        //Reset the last wallpaper position since a new one was added and may have bumped it
        setLastWallpaperId(getLastWallpaperId(), false);
    }

    /**
     * Del image object image object.
     *
     * @param id the id
     */
    public synchronized void delImageObject(String id) {
        ImageObject deadImgWalking = referenceImages.get(id);
        referenceImages.remove(id);
        for (Collection<ImageObject> imgArray : sortedImages){
            imgArray.remove(deadImgWalking);
        }
        if (deadImgWalking != null && store.getLastWallpaperId().equals(deadImgWalking.getId())) {
            lastWallpaperId = "";
        } else {
            //Reset the last wallpaper position to the repositioned place
            store.setLastWallpaperId(store.getLastWallpaperId(), false);
        }
    }

    /**
     * Gets image object.
     *
     * @param id the id
     * @return the image object
     */
    public synchronized ImageObject getImageObject(String id) {
        return referenceImages.get(id);
    }

    public synchronized ImageObject getImageObjectByName(String name) {
        for (ImageObject img : referenceImages.values()) {
            if (img.getName().equals(name))
                return img;
        }
        return null;
    }

    /**
     * Gets image object.
     *
     * @param i the
     * @return the image object
     */
    public synchronized ImageObject getImageObject(int i) {
        if (i < 0 || i >= getImageObjectArray().length)
            return null;
        return getImageObjectArray()[i];
    }

    /**
     * Get image object array image object [ ].
     *
     * @return the image object [ ]
     */
    public synchronized ImageObject[] getImageObjectArray() {
        if (sortCriteria == SORT_BY_ADDED)
            return referenceImages.values().toArray(new ImageObject[0]);
        else
            return sortedImages.get(sortCriteria).toArray(new ImageObject[0]);
    }

    /**
     * @param id Unique ID of the ImageObject
     * @return position in image store, or -1 if not found
     */
    public synchronized int getPosition(String id) {
        ImageObject[] objs = getImageObjectArray();
        int pos = -1;
        for (int i = 0; i < objs.length; i++) {
            if (objs[i].getId().equals(id))
                pos = i;
        }
        return pos;
    }

    public synchronized int getReferencePosition(String id) {
        ImageObject[] objs = referenceImages.values().toArray(new ImageObject[0]);
        int pos = -1;
        for (int i = 0; i < objs.length; i++) {
            if (objs[i].getId().equals(id))
                pos = i;
        }
        return pos;
    }

    public synchronized int getCustomPosition(String id) {
        ImageObject[] objs = sortedImages.get(SORT_BY_CUSTOM).toArray(new ImageObject[0]);
        int pos = -1;
        for (int i = 0; i < objs.length; i++) {
            if (objs[i].getId().equals(id))
                pos = i;
        }
        return pos;
    }

    /**
     * Clear.
     */
    public synchronized void clear() {
        referenceImages.clear();
        setLastWallpaperId("", false);
        for (Collection<ImageObject> imgArray : sortedImages){
            imgArray.clear();
        }
    }

    /**
     * Size int.
     *
     * @return the int
     */
    public synchronized int size() {
        return referenceImages.size();
    }


    public void addWallpaperAddedListener(WallpaperAddedListener wal) {
        wallpaperListeners.add(wal);
    }

    public void removeWallpaperAddedListener(WallpaperAddedListener wal) {
        wallpaperListeners.remove(wal);
    }

    /**
     * Add wallpapers from list of URI's.
     * Loading dialog is displayed and progress bar updated as wallpapers are added.
     *
     * @param sources the sources
     */
    public void addWallpapers(Context context, HashSet<Uri> sources) {
        boolean recompress = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.preference_recompress), false);
        loadingErrors = new HashSet<>();
        if (sources.size() > 0) {
            loadingDoneSignal = new CountDownLatch(sources.size());
            for (WallpaperAddedListener wal : wallpaperListeners)
                wal.onWallpaperLoadingStarted(sources.size());
            for (Uri uri : sources) {
                Thread t = new Thread(() -> {
                    File fImageStorageFolder = StorageUtils.getStorageFolder(context);
                    StatFs stats = new StatFs(fImageStorageFolder.getAbsolutePath());
                    long bytesAvailable = stats.getAvailableBlocksLong() * stats.getBlockSizeLong();
                    if (!fImageStorageFolder.exists() && !fImageStorageFolder.mkdirs())
                        loadingErrors.add(context.getString(R.string.loading_error_cannot_mkdir));
                    else if (bytesAvailable < MINIMUM_REQUIRED_FREE_SPACE)
                        loadingErrors.add(context.getString(R.string.loading_error_precheck_low_space));
                    else {
                        String hash = StorageUtils.getHash(context, uri);
                        if (hash == null)
                            hash = UUID.randomUUID().toString();
                        if (getImageObject(hash) == null) {
                            String name = StorageUtils.getFileAttrib(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME, context);
                            String sDate = StorageUtils.getFileAttrib(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, context);
                            String type = context.getContentResolver().getType(uri);
                            if (type.startsWith("image/")) {
                                try {
                                    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
                                    String filename = name + "_" + uuid;
                                    long size = Long.parseLong(StorageUtils.getFileAttrib(uri, DocumentsContract.Document.COLUMN_SIZE, context));
                                    Uri uCopiedFile = StorageUtils.saveBitmap(context, uri, size, fImageStorageFolder.getPath(), filename, recompress);
                                    if (recompress) type = "image/webp";
                                    size = StorageUtils.getFileSize(uCopiedFile);
                                    try {
                                        ImageObject img = new ImageObject(uCopiedFile, hash, filename, size, type, new Date(Long.parseLong(sDate)));
                                        img.generateThumbnail(context);
                                        img.setColor(img.getColorFromBitmap(context));
                                        addImageObject(img);
                                        for (WallpaperAddedListener wal : wallpaperListeners)
                                            wal.onWallpaperAdded(img);
                                    } catch (NoSuchAlgorithmException | IOException e) {
                                        e.printStackTrace();
                                    }
                                } catch (FileNotFoundException e) {
                                    loadingErrors.add(context.getString(R.string.loading_error_fnf));
                                } catch (IOException e) {
                                    loadingErrors.add(context.getString(R.string.loading_error_out_of_space));
                                }
                            } else {
                                loadingErrors.add(context.getString(R.string.loading_error_not_an_image));
                            }
                        }
                    }
                    for (WallpaperAddedListener wal : wallpaperListeners)
                        wal.onWallpaperLoadingIncrement(1);
                    loadingDoneSignal.countDown();
                });
                BackgroundExecutor.getExecutor().execute(t);
            }
            // UI work that waits for the image loading to complete
            BackgroundExecutor.getExecutor().execute(() -> {
                try {
                    loadingDoneSignal.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    saveToPrefs(context.getApplicationContext());
                    StringBuilder sb = new StringBuilder();
                    for (String str : loadingErrors) {
                        sb.append(str);
                        sb.append(System.getProperty("line.separator"));
                    }
                    for (WallpaperAddedListener wal : wallpaperListeners)
                        wal.onWallpaperLoadingFinished(((loadingErrors.size()) == 0) ? WallpaperAddedListener.SUCCESS : WallpaperAddedListener.ERROR, sb.toString());
                }
            });
        }
    }

    public int getSortCriteria() {
        return sortCriteria;
    }

    public void setSortCriteria(int sortCriteria) {
        this.sortCriteria = sortCriteria;
        if (!lastWallpaperId.equals("")) {
            //Update the position of the last wallpaper
            setLastWallpaperId(lastWallpaperId, false);
        }
        for (ImageStoreSortListener sl : sortListeners) {
            sl.onImageStoreSortChanged();
        }
    }

    public void moveImage(ImageObject object, int newPos){
        LinkedList<ImageObject> sortedSet = (LinkedList<ImageObject>) sortedImages.get(SORT_BY_CUSTOM);
        if (sortedSet.contains(object)){
            sortedSet.remove(object);
            sortedSet.add(newPos, object);
        }
    }

    public void addSortListener(ImageStoreSortListener sl) {
        sortListeners.add(sl);
    }

    public void removeSortListener(ImageStoreSortListener sl) {
        sortListeners.remove(sl);
    }

    public interface ImageStoreSortListener {
        void onImageStoreSortChanged();
    }

    public interface WallpaperAddedListener {
        int SUCCESS = 0;
        int ERROR = 1;

        void onWallpaperAdded(ImageObject img);

        void onWallpaperLoadingStarted(int size);

        void onWallpaperLoadingIncrement(int inc);

        void onWallpaperLoadingFinished(int status, String message);
    }
}
