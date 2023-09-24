package org.telegram.ui.Stories;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.SparseArray;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import com.google.android.exoplayer2.util.Consumer;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.StatisticActivity;
import org.telegram.ui.Stories.recorder.DraftsController;
import org.telegram.ui.Stories.recorder.StoryEntry;
import org.telegram.ui.Stories.recorder.StoryPrivacyBottomSheet;
import org.telegram.ui.Stories.recorder.StoryRecorder;
import org.telegram.ui.Stories.recorder.StoryUploadingService;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

import tw.nekomimi.nekogram.NekoConfig;

public class StoriesController {

    public final static int STATE_READ = 0;
    public final static int STATE_UNREAD = 1;
    public final static int STATE_UNREAD_CLOSE_FRIEND = 2;

    private final int currentAccount;
    private final LongSparseArray<ArrayList<UploadingStory>> uploadingStoriesByDialogId = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<UploadingStory>> uploadingAndEditingStories = new LongSparseArray<>();
    private final LongSparseArray<HashMap<Integer, UploadingStory>> editingStories = new LongSparseArray<>();
    public LongSparseIntArray dialogIdToMaxReadId = new LongSparseIntArray();

    TLRPC.PeerStories currentUserStories;

    private ArrayList<TLRPC.PeerStories> dialogListStories = new ArrayList<>();
    private ArrayList<TLRPC.PeerStories> hiddenListStories = new ArrayList<>();
    private LongSparseArray<TLRPC.PeerStories> allStoriesMap = new LongSparseArray();
    private LongSparseIntArray loadingDialogsStories = new LongSparseIntArray();
    StoriesStorage storiesStorage;
    SharedPreferences mainSettings;
    final LongSparseArray<ViewsForPeerStoriesRequester> pollingViewsForSelfStoriesRequester = new LongSparseArray<>();

    public final static Comparator<TLRPC.StoryItem> storiesComparator = Comparator.comparingInt(o -> o.date);

    //load all stories once and manage they by updates
    //reload only if user get diffToLong
    boolean allStoriesLoaded;
    boolean allHiddenStoriesLoaded;
    boolean loadingFromDatabase;
    String state = "";
    boolean hasMore;
    private boolean loadingFromServer;
    private boolean loadingFromServerHidden;
    private boolean storiesReadLoaded;
    private int totalStoriesCount;
    private int totalStoriesCountHidden;

    private final DraftsController draftsController;


    public SparseArray<SelfStoryViewsPage.ViewsModel> selfViewsModel = new SparseArray<>();
    private String stateHidden;
    private boolean hasMoreHidden = true;
    private boolean firstLoad = true;
    private TLRPC.TL_storiesStealthMode stealthMode;

    public StoriesController(int currentAccount) {
        this.currentAccount = currentAccount;
        storiesStorage = new StoriesStorage(currentAccount);
        mainSettings = MessagesController.getInstance(currentAccount).getMainSettings();
        state = mainSettings.getString("last_stories_state", "");
        stateHidden = mainSettings.getString("last_stories_state_hidden", "");
        totalStoriesCountHidden = mainSettings.getInt("total_stores_hidden", 0);
        totalStoriesCount = mainSettings.getInt("total_stores", 0);
        storiesReadLoaded = mainSettings.getBoolean("read_loaded", false);
        stealthMode = readStealthMode(mainSettings.getString("stories_stealth_mode", null));
        storiesStorage.getMaxReadIds(longSparseIntArray -> dialogIdToMaxReadId = longSparseIntArray);

        sortStoriesRunnable = () -> {
            sortDialogStories(dialogListStories);
            sortDialogStories(hiddenListStories);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
        };

        draftsController = new DraftsController(currentAccount);
    }

    private TLRPC.TL_storiesStealthMode readStealthMode(String string) {
        if (string == null) {
            return null;
        }
        SerializedData serializedData = new SerializedData(Utilities.hexToBytes(string));
        try {
            TLRPC.TL_storiesStealthMode storiesStealthMode = TLRPC.TL_storiesStealthMode.TLdeserialize(serializedData, serializedData.readInt32(true), true);

            return storiesStealthMode;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private void writeStealthMode(TLRPC.TL_storiesStealthMode mode) {
        SharedPreferences.Editor editor = MessagesController.getInstance(currentAccount).getMainSettings().edit();
        if (mode == null) {
            editor.remove("stories_stealth_mode").apply();
            return;
        }
        SerializedData data = new SerializedData(mode.getObjectSize());
        mode.serializeToStream(data);
        editor.putString("stories_stealth_mode", Utilities.bytesToHex(data.toByteArray())).apply();
    }

    public void loadAllStories() {
        if (!firstLoad) {
            loadStories();
            loadStoriesRead();
        }
    }

    private void loadStoriesRead() {
        if (storiesReadLoaded) {
            return;
        }
        TLRPC.TL_stories_getAllReadPeerStories allReadUserStories = new TLRPC.TL_stories_getAllReadPeerStories();
        ConnectionsManager.getInstance(currentAccount).sendRequest(allReadUserStories, (response, error) -> {
            TLRPC.Updates updates = (TLRPC.Updates) response;
            if (updates == null) {
                return;
            }
            MessagesController.getInstance(currentAccount).processUpdateArray(updates.updates, updates.users, updates.chats, false, updates.date);
            AndroidUtilities.runOnUIThread(() -> {
                storiesReadLoaded = true;
                mainSettings.edit().putBoolean("read_loaded", true).apply();
            });
        });
    }

    private void sortDialogStories(ArrayList<TLRPC.PeerStories> list) {
        fixDeletedAndNonContactsStories(list);
        Collections.sort(list, peerStoriesComparator);
    }

    private void fixDeletedAndNonContactsStories(ArrayList<TLRPC.PeerStories> list) {
        for (int k = 0; k < list.size(); k++) {
            TLRPC.PeerStories userStories = list.get(k);
            long dialogId = DialogObject.getPeerDialogId(userStories.peer);
            boolean removed = false;
            if (dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                if (user != null && !isContactOrService(user)) {
                    list.remove(k);
                    k--;
                    removed = true;
                }
            }
            for (int i = 0; i < userStories.stories.size(); i++) {
                if (userStories.stories.get(i) instanceof TLRPC.TL_storyItemDeleted) {
                    userStories.stories.remove(i);
                    i--;
                }
            }
            if (!removed && userStories.stories.isEmpty() && !hasUploadingStories(dialogId)) {
                list.remove(k);
                k--;
            }
        }
    }

    @NonNull
    public DraftsController getDraftsController() {
        return draftsController;
    }

    public boolean hasStories(long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        if (hasUploadingStories(dialogId)) {
            return true;
        }
        if (isLastUploadingFailed(dialogId)) {
            return true;
        }
        TLRPC.PeerStories stories = allStoriesMap.get(dialogId);
        if (stories == null) {
            stories = getStoriesFromFullPeer(dialogId);
        }
        return stories != null && !stories.stories.isEmpty();
    }

    public TLRPC.PeerStories getStoriesFromFullPeer(long dialogId) {
        if (dialogId > 0) {
            TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(dialogId);
            return userFull == null ? null : userFull.stories;
        } else {
            TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(-dialogId);
            return chatFull == null ? null : chatFull.stories;
        }
    }

    public boolean hasStories() {
        if (NekoConfig.hideStories) {
            return false;
        }
        return (dialogListStories != null && dialogListStories.size() > 0) || hasSelfStories();
    }

    public void loadStories() {
        if (firstLoad) {
            loadingFromDatabase = true;
            storiesStorage.getAllStories(allStories -> {
                loadingFromDatabase = false;
                if (allStories != null) {
                    processAllStoriesResponse(allStories, false, true, false);
                    loadFromServer(false);
                    loadFromServer(true);
                } else {
                    cleanup();
                    loadStories();
                }
            });
        } else {
            loadFromServer(false);
            loadFromServer(true);
        }
        firstLoad = false;
    }

    public void loadHiddenStories() {
        if (hasMoreHidden) {
            loadFromServer(true);
        }
    }

    public void toggleHidden(long dialogId, boolean hide, boolean request, boolean notify) {
        ArrayList<TLRPC.PeerStories> removeFrom;
        ArrayList<TLRPC.PeerStories> insertTo;
        boolean remove = true;
        if (hide) {
          //  remove = true;
            removeFrom = dialogListStories;
            insertTo = hiddenListStories;
        } else {
            removeFrom = hiddenListStories;
            insertTo = dialogListStories;
        }

        TLRPC.PeerStories removed = null;
        for (int i = 0; i < removeFrom.size(); i++) {
            if (DialogObject.getPeerDialogId(removeFrom.get(i).peer) == dialogId) {
                if (remove) {
                    removed = removeFrom.remove(i);
                } else {
                    removed = removeFrom.get(i);
                }
                break;
            }
        }
        if (removed != null) {
            boolean found = false;
            for (int i = 0; i < insertTo.size(); i++) {
                if (DialogObject.getPeerDialogId(insertTo.get(i).peer) == dialogId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                insertTo.add(0, removed);
                AndroidUtilities.cancelRunOnUIThread(sortStoriesRunnable);
                sortStoriesRunnable.run();
            }
        }
        if (notify) {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
        }
        MessagesController.getInstance(currentAccount).checkArchiveFolder();
        if (request) {
            if (dialogId >= 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                user.stories_hidden = hide;
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(Collections.singletonList(user), null, false, true);
                MessagesController.getInstance(currentAccount).putUser(user, false);
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                chat.stories_hidden = hide;
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(null, Collections.singletonList(chat), false, true);
                MessagesController.getInstance(currentAccount).putChat(chat, false);
            }
            TLRPC.TL_stories_togglePeerStoriesHidden req = new TLRPC.TL_stories_togglePeerStoriesHidden();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.hidden = hide;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            });
        }
    }

    private void loadFromServer(boolean hidden) {
        if ((hidden && loadingFromServerHidden) || (!hidden && loadingFromServer) || loadingFromDatabase) {
            return;
        }
        if (hidden) {
            loadingFromServerHidden = true;
        } else {
            loadingFromServer = true;
        }
        TLRPC.TL_stories_getAllStories req = new TLRPC.TL_stories_getAllStories();
        String state = hidden ? stateHidden : this.state;
        boolean hasMore = hidden ? hasMoreHidden : this.hasMore;
        if (!TextUtils.isEmpty(state)) {
            req.state = state;
            req.flags |= 1;
        }
        boolean isNext = false;
        if (hasMore && !TextUtils.isEmpty(state)) {
            isNext = req.next = true;
        }
        req.include_hidden = hidden;
        boolean finalIsNext = isNext;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (hidden) {
                loadingFromServerHidden = false;
            } else {
                loadingFromServer = false;
            }
            FileLog.d("StoriesController loaded stories from server state=" + req.state + " more=" + req.next + "  " + response);
            if (response instanceof TLRPC.TL_stories_allStories) {
                TLRPC.TL_stories_allStories storiesResponse = (TLRPC.TL_stories_allStories) response;
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(storiesResponse.users, null, true, true);
                if (!hidden) {
                    this.totalStoriesCount = ((TLRPC.TL_stories_allStories) response).count;
                    this.hasMore = ((TLRPC.TL_stories_allStories) response).has_more;
                    this.state = storiesResponse.state;
                    mainSettings.edit().putString("last_stories_state", this.state)
                            .putBoolean("last_stories_has_more", this.hasMore)
                            .putInt("total_stores", this.totalStoriesCount)
                            .apply();
                } else {
                    this.totalStoriesCountHidden = ((TLRPC.TL_stories_allStories) response).count;
                    this.hasMoreHidden = ((TLRPC.TL_stories_allStories) response).has_more;
                    this.stateHidden = storiesResponse.state;
                    mainSettings.edit().putString("last_stories_state_hidden", this.stateHidden)
                            .putBoolean("last_stories_has_more_hidden", this.hasMoreHidden)
                            .putInt("total_stores_hidden", this.totalStoriesCountHidden)
                            .apply();
                }
                processAllStoriesResponse(storiesResponse, hidden, false, finalIsNext);
            } else if (response instanceof TLRPC.TL_stories_allStoriesNotModified) {
                if (!hidden) {
                    this.hasMore = mainSettings.getBoolean("last_stories_has_more", false);
                    this.state = ((TLRPC.TL_stories_allStoriesNotModified) response).state;
                    mainSettings.edit().putString("last_stories_state", this.state).apply();
                } else {
                    this.hasMoreHidden = mainSettings.getBoolean("last_stories_has_more_hidden", false);
                    this.stateHidden = ((TLRPC.TL_stories_allStoriesNotModified) response).state;
                    mainSettings.edit().putString("last_stories_state_hidden", this.stateHidden).apply();
                }
                boolean hasMoreLocal = hidden ? hasMoreHidden : this.hasMore;
                if (hasMoreLocal) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
                }
            }
        }));
    }

    private void processAllStoriesResponse(TLRPC.TL_stories_allStories storiesResponse, boolean hidden, boolean fromCache, boolean isNext) {
        if (!isNext) {
            if (!hidden) {
                //allStoriesMap.clear();
                dialogListStories.clear();
            } else {
                hiddenListStories.clear();
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < storiesResponse.peer_stories.size(); i++) {
                if (builder.length() != 0) {
                    builder.append(", ");
                }
                long dialogId = DialogObject.getPeerDialogId(storiesResponse.peer_stories.get(i).peer);
                builder.append(dialogId);
            }
            FileLog.d("StoriesController cache=" + fromCache + " hidden=" + hidden + " processAllStoriesResponse {" + builder + "}");
        }
        MessagesController.getInstance(currentAccount).putUsers(storiesResponse.users, fromCache);
        MessagesController.getInstance(currentAccount).putChats(storiesResponse.chats, fromCache);

        for (int i = 0; i < storiesResponse.peer_stories.size(); i++) {
            TLRPC.PeerStories userStories = storiesResponse.peer_stories.get(i);
            long dialogId = DialogObject.getPeerDialogId(userStories.peer);
            for (int j = 0; j < userStories.stories.size(); j++) {
                if (userStories.stories.get(j) instanceof TLRPC.TL_storyItemDeleted) {
                    NotificationsController.getInstance(currentAccount).processDeleteStory(dialogId, userStories.stories.get(j).id);
                    userStories.stories.remove(j);
                    j--;
                }
            }
            if (!userStories.stories.isEmpty()) {
                allStoriesMap.put(dialogId, userStories);
                for (int k = 0; k < 2; k++) {
                    ArrayList<TLRPC.PeerStories> storiesList = k == 0 ? hiddenListStories : dialogListStories;
                    for (int j = 0; j < storiesList.size(); j++) {
                        if (DialogObject.getPeerDialogId(storiesList.get(j).peer) == dialogId) {
                            storiesList.remove(j);
                            break;
                        }
                    }
                }
                if (dialogId > 0) {
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                    if (user == null) {
                        continue;
                    }
                    if (user.stories_hidden) {
                        addUserToHiddenList(userStories);
                    } else {
                        dialogListStories.add(userStories);
                        preloadUserStories(userStories);
                    }
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                    if (chat == null) {
                        continue;
                    }
                    if (chat.stories_hidden) {
                        addUserToHiddenList(userStories);
                    } else {
                        dialogListStories.add(userStories);
                        preloadUserStories(userStories);
                    }
                }
            } else {
                allStoriesMap.remove(dialogId);
            }
        }
        if (!fromCache) {
            storiesStorage.saveAllStories(storiesResponse.peer_stories, isNext, hidden, () -> {
//                if (!hidden) {
//                    FileLog.d("StoriesController all stories loaded");
//                    allStoriesLoaded = true;
//                    mainSettings.edit().putBoolean("stories_loaded", true).apply();
//                } else {
//                    FileLog.d("StoriesController all hidden stories loaded");
//                    allHiddenStoriesLoaded = true;
//                    mainSettings.edit().putBoolean("stories_loaded_hidden", true).apply();
//                }
            });
        }
        sortUserStories();
    }

    private void addUserToHiddenList(TLRPC.PeerStories userStories) {
        boolean found = false;
        long dialogId = DialogObject.getPeerDialogId(userStories.peer);
        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return;
        }
        for (int i = 0; i < hiddenListStories.size(); i++) {
            if (DialogObject.getPeerDialogId(hiddenListStories.get(i).peer) == dialogId) {
                found = true;
            }
        }
        if (!found) {
            hiddenListStories.add(userStories);
        }
        MessagesController.getInstance(currentAccount).checkArchiveFolder();
    }

    private void sortUserStories() {
        AndroidUtilities.cancelRunOnUIThread(sortStoriesRunnable);
        sortStoriesRunnable.run();
    }

    public void preloadUserStories(TLRPC.PeerStories userStories) {
        int preloadPosition = 0;
        for (int i = 0; i < userStories.stories.size(); i++) {
            if (userStories.stories.get(i).id > userStories.max_read_id) {
                preloadPosition = i;
                break;
            }
        }
        if (userStories.stories.isEmpty()) {
            return;
        }
        long dialogId = DialogObject.getPeerDialogId(userStories.peer);
        preloadStory(dialogId, userStories.stories.get(preloadPosition));
        if (preloadPosition > 0) {
            preloadStory(dialogId, userStories.stories.get(preloadPosition - 1));
        }
        if (preloadPosition < userStories.stories.size() - 1) {
            preloadStory(dialogId, userStories.stories.get(preloadPosition + 1));
        }
    }

    private void preloadStory(long dialogId, TLRPC.StoryItem storyItem) {
        if (storyItem.attachPath != null) {
            return;
        }
        boolean canPreloadStories = DownloadController.getInstance(currentAccount).canPreloadStories();
        if (!canPreloadStories) {
            return;
        }
        boolean isVideo = storyItem.media != null && MessageObject.isVideoDocument(storyItem.media.document);
        storyItem.dialogId = dialogId;
        if (isVideo) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(storyItem.media.document.thumbs, 1000);
            FileLoader.getInstance(currentAccount).loadFile(storyItem.media.document, storyItem, FileLoader.PRIORITY_LOW, 1);
            FileLoader.getInstance(currentAccount).loadFile(ImageLocation.getForDocument(size, storyItem.media.document), storyItem, "jpg", FileLoader.PRIORITY_LOW, 1);
        } else {
            TLRPC.Photo photo = storyItem.media == null ? null : storyItem.media.photo;
            if (photo != null && photo.sizes != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
                FileLoader.getInstance(currentAccount).loadFile(ImageLocation.getForPhoto(size, photo), storyItem, "jpg", FileLoader.PRIORITY_LOW, 1);
            }
        }
    }

    public void uploadStory(StoryEntry entry, boolean count) {
        UploadingStory uploadingStory = new UploadingStory(entry);
        if (count) {
            long dialogId = uploadingStory.dialogId;
            if (entry.isEdit) {
                HashMap<Integer, UploadingStory> editigStoriesMap = editingStories.get(dialogId);
                if (editigStoriesMap == null) {
                    editigStoriesMap = new HashMap<>();
                    editingStories.put(dialogId, editigStoriesMap);
                }
                editigStoriesMap.put(entry.editStoryId, uploadingStory);
            } else {
                addUploadingStoryToList(dialogId, uploadingStory, uploadingStoriesByDialogId);
            }
            addUploadingStoryToList(dialogId, uploadingStory, uploadingAndEditingStories);
            if (dialogId != UserConfig.getInstance(currentAccount).clientUserId) {
                boolean found = false;
                for (int i = 0; i < dialogListStories.size(); i++) {
                    if (DialogObject.getPeerDialogId(dialogListStories.get(i).peer) == dialogId) {
                        found = true;
                        TLRPC.PeerStories peerStories = dialogListStories.remove(i);
                        dialogListStories.add(0, peerStories);
                        break;
                    }
                }
                if (!found) {
                    for (int i = 0; i < hiddenListStories.size(); i++) {
                        if (DialogObject.getPeerDialogId(hiddenListStories.get(i).peer) == dialogId) {
                            found = true;
                            TLRPC.PeerStories peerStories = hiddenListStories.remove(i);
                            hiddenListStories.add(0, peerStories);
                            break;
                        }
                    }
                }
                if (!found) {
                    TLRPC.PeerStories peerStories = new TLRPC.TL_peerStories();
                    peerStories.peer = MessagesController.getInstance(currentAccount).getPeer(dialogId);
                    allStoriesMap.put(dialogId, peerStories);
                    dialogListStories.add(0, peerStories);
                    loadAllStoriesForDialog(dialogId);
                }
            }
        }
        uploadingStory.start();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }

    private void addUploadingStoryToList(long dialogId, UploadingStory uploadingStory, LongSparseArray<ArrayList<UploadingStory>> sparseArray) {
        ArrayList<StoriesController.UploadingStory> arrayList = sparseArray.get(dialogId);
        if (arrayList == null) {
            arrayList = new ArrayList<>();
            sparseArray.put(dialogId, arrayList);
        }
        arrayList.add(uploadingStory);
    }

    public void putUploadingDrafts(ArrayList<StoryEntry> entries) {
        for (StoryEntry entry : entries) {
            UploadingStory uploadingStory = new UploadingStory(entry);
            long dialogId = uploadingStory.dialogId;
            addUploadingStoryToList(dialogId, uploadingStory, uploadingStoriesByDialogId);
        }
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }

    public ArrayList<TLRPC.PeerStories> getDialogListStories() {
        return dialogListStories;
    }

    public TLRPC.PeerStories getStories(long peerId) {
        return allStoriesMap.get(peerId);
    }

    public ArrayList<UploadingStory> getUploadingStories(long dialogId) {
        return uploadingStoriesByDialogId.get(dialogId);
    }

    public boolean isLastUploadingFailed(long dialogId) {
        ArrayList<UploadingStory> uploadingStories = uploadingStoriesByDialogId.get(dialogId);
        if (uploadingStories != null && !uploadingStories.isEmpty()) {
            return uploadingStories.get(uploadingStories.size() - 1).failed;
        }
        return false;
    }

    public ArrayList<UploadingStory> getUploadingAndEditingStories(long dialogId) {
        return uploadingAndEditingStories.get(dialogId);
    }

    public int getMyStoriesCount() {
        int count = uploadingAndEditingStories.size();
        TLRPC.PeerStories userStories = getStories(getSelfUserId());
        if (userStories != null && userStories.stories != null) {
            count += userStories.stories.size();
        }
        return count;
    }

    public UploadingStory findEditingStory(long dialogId, TLRPC.StoryItem storyItem) {
        if (storyItem == null) {
            return null;
        }
        HashMap<Integer, UploadingStory> editingStoriesMap = editingStories.get(dialogId);
        if (editingStoriesMap == null || editingStoriesMap.isEmpty()) {
            return null;
        }
        return editingStoriesMap.get(storyItem.id);
    }

    public UploadingStory getEditingStory(long dialogId) {
        HashMap<Integer, UploadingStory> editingStoriesMap = editingStories.get(dialogId);
        if (editingStoriesMap == null || editingStoriesMap.isEmpty()) {
            return null;
        }
        Collection<UploadingStory> values = editingStoriesMap.values();
        if (values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }

    private void applyNewStories(TLRPC.PeerStories stories) {
        long dialogId = DialogObject.getPeerDialogId(stories.peer);
        allStoriesMap.put(dialogId, stories);
        if (dialogId != UserConfig.getInstance(UserConfig.selectedAccount).clientUserId) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            applyToList(stories);
            if (user != null && !user.stories_hidden) {
                preloadUserStories(stories);
            }
        }
        FileLog.d("StoriesController applyNewStories " + dialogId);
        updateStoriesInLists(dialogId, stories.stories);
    }

    public static TLRPC.StoryItem applyStoryUpdate(TLRPC.StoryItem oldStoryItem, TLRPC.StoryItem newStoryItem) {
        if (newStoryItem == null) {
            return oldStoryItem;
        }
        if (oldStoryItem == null) {
            return newStoryItem;
        }
        if (newStoryItem.min) {
            oldStoryItem.pinned = newStoryItem.pinned;
            oldStoryItem.isPublic = newStoryItem.isPublic;
            oldStoryItem.close_friends = newStoryItem.close_friends;
            if (newStoryItem.date != 0) {
                oldStoryItem.date = newStoryItem.date;
            }
            if (newStoryItem.expire_date != 0) {
                oldStoryItem.expire_date = newStoryItem.expire_date;
            }
            oldStoryItem.caption = newStoryItem.caption;
            oldStoryItem.entities = newStoryItem.entities;
            if (newStoryItem.media != null) {
                oldStoryItem.media = newStoryItem.media;
            }
            // privacy and views shouldn't be copied when min=true
            return oldStoryItem;
        }
        return newStoryItem;
    }

    public void processUpdate(TLRPC.TL_updateStory updateStory) {
        //stage queue
        if (updateStory.story == null) {
            return;
        }
        long dialogId = DialogObject.getPeerDialogId(updateStory.peer);
        if (dialogId == 0) {
            FileLog.d("StoriesController can't update story dialogId == 0");
            return;
        }
        TLRPC.User user = null;
        if (dialogId > 0) {
            user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user != null && (isContactOrService(user) || user.self)) {
                storiesStorage.processUpdate(updateStory);
            }
        } else {
            storiesStorage.processUpdate(updateStory);
        }
        TLRPC.User finalUser = user;
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.PeerStories currentUserStory = allStoriesMap.get(dialogId);
            FileLog.d("StoriesController update stories for dialog " + dialogId);
            updateStoriesInLists(dialogId, Collections.singletonList(updateStory.story));

            ArrayList<TLRPC.StoryItem> newStoryItems = new ArrayList<>();
            int oldStoriesCount = totalStoriesCount;
            boolean notify = false;
            if (currentUserStory != null) {
                boolean changed = false;
                TLRPC.StoryItem newStory = updateStory.story;
                if (newStory instanceof TLRPC.TL_storyItemDeleted) {
                    NotificationsController.getInstance(currentAccount).processDeleteStory(dialogId, newStory.id);
                }
                boolean found = false;
                for (int i = 0; i < currentUserStory.stories.size(); i++) {
                    if (currentUserStory.stories.get(i).id == newStory.id) {
                        found = true;
                        if (newStory instanceof TLRPC.TL_storyItemDeleted) {
                            currentUserStory.stories.remove(i);
                            FileLog.d("StoriesController remove story id=" + newStory.id);
                            changed = true;
                        } else {
                            TLRPC.StoryItem oldStory = currentUserStory.stories.get(i);
                            newStory = applyStoryUpdate(oldStory, newStory);
                            newStoryItems.add(newStory);
                            currentUserStory.stories.set(i, newStory);
                            if (newStory.attachPath == null) {
                                newStory.attachPath = oldStory.attachPath;
                            }
                            if (newStory.firstFramePath == null) {
                                newStory.firstFramePath = oldStory.firstFramePath;
                            }
                            FileLog.d("StoriesController update story id=" + newStory.id);
                        }
                        break;
                    }
                }
                if (!found) {
                    if (newStory instanceof TLRPC.TL_storyItemDeleted) {
                        FileLog.d("StoriesController can't add new story DELETED");
                        return;
                    }
                    if (StoriesUtilities.isExpired(currentAccount, newStory)) {
                        FileLog.d("StoriesController can't add new story isExpired");
                        return;
                    }
                    if (dialogId > 0 && (finalUser == null || (!finalUser.self && !isContactOrService(finalUser)))) {
                        FileLog.d("StoriesController can't add new story user is not contact");
                        return;
                    }
                    newStoryItems.add(newStory);
                    changed = true;
                    currentUserStory.stories.add(newStory);
                    FileLog.d("StoriesController add new story id=" + newStory.id + " total stories count " + currentUserStory.stories.size());
                    preloadStory(dialogId, newStory);
                    notify = true;
                    applyToList(currentUserStory);
                }
                if (changed) {
                    if (currentUserStory.stories.isEmpty() && !hasUploadingStories(dialogId)) {
                        dialogListStories.remove(currentUserStory);
                        hiddenListStories.remove(currentUserStory);
                        allStoriesMap.remove(DialogObject.getPeerDialogId(currentUserStory.peer));
                        totalStoriesCount--;
                    } else {
                        Collections.sort(currentUserStory.stories, storiesComparator);
                    }
                    notify = true;
                }
            } else {
                if (updateStory.story instanceof TLRPC.TL_storyItemDeleted) {
                    FileLog.d("StoriesController can't add user " + dialogId + " with new story DELETED");
                    return;
                }
                if (StoriesUtilities.isExpired(currentAccount, updateStory.story)) {
                    FileLog.d("StoriesController can't add user " + dialogId + " with new story isExpired");
                    return;
                }
                if (dialogId > 0 && (finalUser == null || (!finalUser.self && !isContactOrService(finalUser)))) {
                    FileLog.d("StoriesController can't add user cause is not contact");
                    return;
                }
                currentUserStory = new TLRPC.TL_peerStories();
                currentUserStory.peer = updateStory.peer;
                currentUserStory.stories.add(updateStory.story);
                FileLog.d("StoriesController add new user with story id=" + updateStory.story.id);
                applyNewStories(currentUserStory);
                notify = true;
                totalStoriesCount++;
                loadAllStoriesForDialog(dialogId);
            }
            if (oldStoriesCount != totalStoriesCount) {
                mainSettings.edit().putInt("total_stores", totalStoriesCount).apply();
            }
            fixDeletedAndNonContactsStories(dialogListStories);
            fixDeletedAndNonContactsStories(hiddenListStories);
            if (notify) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
            }
            MessagesController.getInstance(currentAccount).checkArchiveFolder();
        });
    }

    private boolean isContactOrService(TLRPC.User user) {
        return user != null && (user.contact || user.id == MessagesController.getInstance(currentAccount).storiesChangelogUserId);
    }

    private void applyToList(TLRPC.PeerStories currentUserStory) {
        long dialogId = DialogObject.getPeerDialogId(currentUserStory.peer);
        TLRPC.User user = null;
        TLRPC.Chat chat = null;
        if (dialogId > 0) {
            user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user == null) {
                FileLog.d("StoriesController can't apply story user == null");
                return;
            }
        } else {
            chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat == null) {
                FileLog.d("StoriesController can't apply story chat == null");
                return;
            }
        }
        boolean found = false;
        for (int i = 0; i < dialogListStories.size(); i++) {
            if (DialogObject.getPeerDialogId(dialogListStories.get(i).peer) == dialogId) {
                dialogListStories.remove(i);
                found = true;
                break;
            }
        }
        for (int i = 0; i < hiddenListStories.size(); i++) {
            if (DialogObject.getPeerDialogId(hiddenListStories.get(i).peer) == dialogId) {
                hiddenListStories.remove(i);
                found = true;
                break;
            }
        }
        boolean hidden = (user != null && user.stories_hidden) || (chat != null && chat.stories_hidden);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("StoriesController move user stories to first " + "hidden=" + hidden + " did=" + dialogId);
        }
        if (hidden) {
            hiddenListStories.add(0, currentUserStory);
        } else {
            dialogListStories.add(0, currentUserStory);
        }

        if (!found) {
            loadAllStoriesForDialog(dialogId);
        }
        MessagesController.getInstance(currentAccount).checkArchiveFolder();
    }

    HashSet<Long> allStoriesLoading = new HashSet<>();

    private void loadAllStoriesForDialog(long user_id) {
        if (allStoriesLoading.contains(user_id)) {
            return;
        }
        allStoriesLoading.add(user_id);
        FileLog.d("StoriesController loadAllStoriesForDialog " + user_id);
        TLRPC.TL_stories_getPeerStories userStories = new TLRPC.TL_stories_getPeerStories();
        userStories.peer = MessagesController.getInstance(currentAccount).getInputPeer(user_id);
        ConnectionsManager.getInstance(currentAccount).sendRequest(userStories, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            allStoriesLoading.remove(user_id);
            if (response == null) {
                return;
            }
            TLRPC.TL_stories_peerStories stories_userStories = (TLRPC.TL_stories_peerStories) response;
            MessagesController.getInstance(currentAccount).putUsers(stories_userStories.users, false);
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(user_id);
            TLRPC.PeerStories stories = stories_userStories.stories;
            long dialogId = DialogObject.getPeerDialogId(stories.peer);
            allStoriesMap.put(dialogId, stories);
            if (user != null && (isContactOrService(user) || user.self)) {
                applyToList(stories);
                storiesStorage.putPeerStories(stories);
            }

            FileLog.d("StoriesController processAllStoriesResponse dialogId=" + user_id + " overwrite stories " + stories_userStories.stories.stories.size());
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
        }));
    }

    public boolean hasSelfStories() {
        long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
        TLRPC.PeerStories storyItem = allStoriesMap.get(clientUserId);
        if (storyItem != null && !storyItem.stories.isEmpty()) {
            return true;
        }
        if (!Utilities.isNullOrEmpty(uploadingStoriesByDialogId.get(clientUserId))) {
            return true;
        }
        return false;
    }

    public int getSelfStoriesCount() {
        int count = 0;
        TLRPC.PeerStories storyItem = allStoriesMap.get(UserConfig.getInstance(currentAccount).clientUserId);
        if (storyItem != null) {
            count += storyItem.stories.size();
        }
        count += uploadingStoriesByDialogId.size();
        return count;
    }

    public void deleteStory(long dialogId, TLRPC.StoryItem storyItem) {
        if (storyItem == null || storyItem instanceof TLRPC.TL_storyItemDeleted) {
            return;
        }
        for (int k = 0; k < 2; k++) {
            TLRPC.PeerStories stories = null;
            TLRPC.UserFull userFull = null;
            TLRPC.ChatFull chatFull = null;
            if (k == 0) {
                stories = allStoriesMap.get(dialogId);
            } else {
                if (dialogId >= 0) {
                    userFull = MessagesController.getInstance(currentAccount).getUserFull(dialogId);
                    if (userFull != null) {
                        stories = userFull.stories;
                    }
                } else {
                    chatFull = MessagesController.getInstance(currentAccount).getChatFull(-dialogId);
                    if (chatFull != null) {
                        stories = chatFull.stories;
                    }
                }
            }

            if (stories != null) {
                for (int i = 0; i < stories.stories.size(); i++) {
                    if (stories.stories.get(i).id == storyItem.id) {
                        stories.stories.remove(i);
                        if (stories.stories.size() == 0 && !hasUploadingStories(dialogId)) {
                            allStoriesMap.remove(dialogId);
                            dialogListStories.remove(stories);
                            hiddenListStories.remove(stories);
                        }
                        break;
                    }
                }
            }
            if (chatFull != null) {
                MessagesStorage.getInstance(currentAccount).updateChatInfo(chatFull, false);
            }
            if (userFull != null) {
                MessagesStorage.getInstance(currentAccount).updateUserInfo(userFull, false);
            }
        }
        TLRPC.TL_stories_deleteStories req = new TLRPC.TL_stories_deleteStories();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.id.add(storyItem.id);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (error == null) {
                AndroidUtilities.runOnUIThread(this::invalidateStoryLimit);
            }
        });
        storiesStorage.deleteStory(dialogId, storyItem.id);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
        MessagesController.getInstance(currentAccount).checkArchiveFolder();
        updateDeletedStoriesInLists(dialogId, Arrays.asList(storyItem));
    }

    public void deleteStories(ArrayList<TLRPC.StoryItem> storyItems) {
        if (storyItems == null) {
            return;
        }
        TLRPC.TL_stories_deleteStories req = new TLRPC.TL_stories_deleteStories();
        TLRPC.PeerStories stories = allStoriesMap.get(getSelfUserId());
        for (int i = 0; i < storyItems.size(); ++i) {
            TLRPC.StoryItem storyItem = storyItems.get(i);
            if (storyItem instanceof TLRPC.TL_storyItemDeleted) {
                continue;
            }
            if (stories != null) {
                for (int j = 0; j < stories.stories.size(); j++) {
                    if (stories.stories.get(j).id == storyItem.id) {
                        stories.stories.remove(j);
                        if (stories.stories.isEmpty()) {
                            allStoriesMap.remove(getSelfUserId());
                        }
                        break;
                    }
                }
            }
            req.id.add(storyItem.id);
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(this::invalidateStoryLimit);
        });
        updateDeletedStoriesInLists(getSelfUserId(), storyItems);
        storiesStorage.deleteStories(getSelfUserId(), req.id);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }

    public void updateStoriesPinned(long dialogId, ArrayList<TLRPC.StoryItem> storyItems, boolean pinned, Utilities.Callback<Boolean> whenDone) {
        TLRPC.TL_stories_togglePinned req = new TLRPC.TL_stories_togglePinned();
        TLRPC.PeerStories peerStories = getStories(dialogId);
        for (int i = 0; i < storyItems.size(); ++i) {
            TLRPC.StoryItem storyItem = storyItems.get(i);
            if (storyItem instanceof TLRPC.TL_storyItemDeleted) {
                continue;
            }
            storyItem.pinned = pinned;
            // todo: do update stories in one go in database
            req.id.add(storyItem.id);
            if (peerStories != null) {
                for (int j = 0; j < peerStories.stories.size(); j++) {
                    if (peerStories.stories.get(j).id == storyItem.id) {
                        peerStories.stories.get(j).pinned = pinned;
                        storiesStorage.updateStoryItem(dialogId, storyItem);
                    }
                }
            }
        }
        FileLog.d("StoriesController updateStoriesPinned");
        updateStoriesInLists(dialogId, storyItems);

        req.pinned = pinned;
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (whenDone != null) {
                whenDone.run(error == null);
            }
        }));
    }

    private long getSelfUserId() {
        return UserConfig.getInstance(currentAccount).getClientUserId();
    }

    public void updateStoryItem(long dialogId, TLRPC.StoryItem storyItem) {
        FileLog.d("StoriesController updateStoryItem " + dialogId + " " + (storyItem == null ? "null" : storyItem.id + "@" + storyItem.dialogId));
        storiesStorage.updateStoryItem(dialogId, storyItem);
        updateStoriesInLists(dialogId, Collections.singletonList(storyItem));
    }

    public boolean markStoryAsRead(long dialogId, TLRPC.StoryItem storyItem) {
        TLRPC.PeerStories userStories = getStories(dialogId);
        if (userStories == null) {
            userStories = getStoriesFromFullPeer(dialogId);
        }
        return markStoryAsRead(userStories, storyItem, false);
    }

    public boolean markStoryAsRead(TLRPC.PeerStories userStories, TLRPC.StoryItem storyItem, boolean profile) {
        if (storyItem == null || userStories == null) {
            return false;
        }
        final long dialogId = DialogObject.getPeerDialogId(userStories.peer);
        if (storyItem.justUploaded) {
            storyItem.justUploaded = false;
        }
        int currentReadId = dialogIdToMaxReadId.get(dialogId);
        int newReadId = Math.max(userStories.max_read_id, Math.max(currentReadId, storyItem.id));
        NotificationsController.getInstance(currentAccount).processReadStories(dialogId, newReadId);
        userStories.max_read_id = newReadId;
        dialogIdToMaxReadId.put(dialogId, newReadId);
        if (newReadId > currentReadId) {
            if (!profile) {
                storiesStorage.updateMaxReadId(dialogId, newReadId);
            }
            TLRPC.TL_stories_readStories req = new TLRPC.TL_stories_readStories();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            req.max_id = storyItem.id;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {});
            return true;
        }
        return false;
    }

    public void markStoriesAsReadFromServer(long dialogId, int maxStoryId) {
        //stage queue
        AndroidUtilities.runOnUIThread(() -> {
            int maxStoryReadId = Math.max(dialogIdToMaxReadId.get(dialogId, 0), maxStoryId);
            dialogIdToMaxReadId.put(dialogId, maxStoryReadId);
            storiesStorage.updateMaxReadId(dialogId, maxStoryReadId);
            TLRPC.PeerStories userStories = getStories(dialogId);
            if (userStories == null) {
                return;
            }
            if (maxStoryId > userStories.max_read_id) {
                userStories.max_read_id = maxStoryId;
                Collections.sort(dialogListStories, peerStoriesComparator);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
            }
        });
    }

    public boolean hasUnreadStories(long dialogId) {
        TLRPC.PeerStories userStories = allStoriesMap.get(dialogId);
        if (userStories == null) {
            return false;
        }
        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            if (!Utilities.isNullOrEmpty(uploadingStoriesByDialogId.get(dialogId))) {
                return true;
            }
        }
        for (int i = 0; i < userStories.stories.size(); i++) {
//            if (userStories.stories.get(i).justUploaded) {
//                return true;
//            }
            if (userStories.stories.get(i).id > userStories.max_read_id) {
                return true;
            }
        }
        return false;
    }

    public int getUnreadState(long dialogId) {
        return getUnreadState(dialogId, 0);
    }

    public int getUnreadState(long dialogId, int storyId) {
        if (dialogId == 0) {
            return STATE_READ;
        }
        TLRPC.PeerStories peerStories = allStoriesMap.get(dialogId);
        if (peerStories == null) {
            peerStories = getStoriesFromFullPeer(dialogId);
        }
        if (peerStories == null) {
            return STATE_READ;
        }
        if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            if (!Utilities.isNullOrEmpty(uploadingStoriesByDialogId.get(dialogId))) {
                return STATE_UNREAD;
            }
        }
        boolean hasUnread = false;
        int maxReadId = Math.max(peerStories.max_read_id, dialogIdToMaxReadId.get(dialogId, 0));
        for (int i = 0; i < peerStories.stories.size(); i++) {
            if ((storyId == 0 || peerStories.stories.get(i).id == storyId) && peerStories.stories.get(i).id > maxReadId) {
                hasUnread = true;
                if (peerStories.stories.get(i).close_friends) {
                    return STATE_UNREAD_CLOSE_FRIEND;
                }
            }
        }
        if (isLastUploadingFailed(dialogId)) {
            return STATE_READ;
        }
        if (hasUnread) {
            return STATE_UNREAD;
        }

        return STATE_READ;
    }

    public boolean hasUploadingStories(long dialogId) {
        ArrayList<UploadingStory> uploadingStories = uploadingStoriesByDialogId.get(dialogId);
        HashMap<Integer, UploadingStory> editingStoriesMap = editingStories.get(dialogId);
        return (uploadingStories != null && !uploadingStories.isEmpty()) || (editingStoriesMap != null && !editingStoriesMap.isEmpty());
    }

    public void cleanup() {
        allStoriesLoaded = false;
        allHiddenStoriesLoaded = false;
        storiesReadLoaded = false;
        stateHidden = "";
        state = "";
        mainSettings.edit()
                .putBoolean("stories_loaded", false)
                .remove("last_stories_state")
                .putBoolean("stories_loaded_hidden", false)
                .remove("last_stories_state_hidden")
                .putBoolean("read_loaded", false)
                .apply();
        AndroidUtilities.runOnUIThread(draftsController::cleanup);

        loadStories();
        loadStoriesRead();
    }

    public void pollViewsForSelfStories(long dialogId, boolean start) {
        ViewsForPeerStoriesRequester requester = pollingViewsForSelfStoriesRequester.get(dialogId);
        if (requester == null) {
            requester = new ViewsForPeerStoriesRequester(this, dialogId, currentAccount);
            pollingViewsForSelfStoriesRequester.put(dialogId, requester);
        }
        requester.start(start);
    }

    public void stopAllPollers() {
        for (int i = 0; i < pollingViewsForSelfStoriesRequester.size(); i++) {
            pollingViewsForSelfStoriesRequester.valueAt(i).start(false);
        }
    }

    HashSet<Long> loadingAllStories = new HashSet<>();

    void loadSkippedStories(long dialogId) {
        loadSkippedStories(getStories(dialogId), false);
    }

    void loadSkippedStories(TLRPC.PeerStories userStories, boolean profile) {
        if (userStories == null) {
            return;
        }
        final long dialogId = DialogObject.getPeerDialogId(userStories.peer);
        final long key = dialogId * (profile ? -1 : 1);
        if (loadingAllStories.contains(key)) {
            return;
        }
        ArrayList<Integer> storyIdsToLoad = null;
        if (userStories != null) {
            for (int i = 0; i < userStories.stories.size(); i++) {
                if (userStories.stories.get(i) instanceof TLRPC.TL_storyItemSkipped) {
                    if (storyIdsToLoad == null) {
                        storyIdsToLoad = new ArrayList<>();
                    }
                    storyIdsToLoad.add(userStories.stories.get(i).id);
                }
            }
            if (storyIdsToLoad != null) {
                loadingAllStories.add(key);
                TLRPC.TL_stories_getStoriesByID stories = new TLRPC.TL_stories_getStoriesByID();
                stories.id = storyIdsToLoad;
                stories.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                ConnectionsManager.getInstance(currentAccount).sendRequest(stories, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    loadingAllStories.remove(key);
                    TLRPC.PeerStories userStories2 = profile ? userStories : getStories(dialogId);
                    if (userStories2 == null) {
                        return;
                    }
                    if (response instanceof TLRPC.TL_stories_stories) {
                        TLRPC.TL_stories_stories res = (TLRPC.TL_stories_stories) response;
                        for (int i = 0; i < res.stories.size(); i++) {
                            for (int j = 0; j < userStories2.stories.size(); j++) {
                                if (userStories2.stories.get(j).id == res.stories.get(i).id) {
                                    userStories2.stories.set(j, res.stories.get(i));
                                    preloadStory(dialogId, res.stories.get(i));
                                }
                            }
                        }
                        if (!profile) {
                            storiesStorage.updateStories(userStories2);
                        }
                    }
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
                }));
            }
        }
    }

    public void loadNextStories(boolean hidden) {
        if (hasMore) {
            loadFromServer(hidden);
        }
    }

    public void fillMessagesWithStories(LongSparseArray<ArrayList<MessageObject>> messagesWithUnknownStories, Runnable callback, int classGuid) {
        storiesStorage.fillMessagesWithStories(messagesWithUnknownStories, callback, classGuid);
    }

    LongSparseArray<TLRPC.StoryItem> resolvedStories = new LongSparseArray<>();

    public void resolveStoryLink(long peerId, int storyId, Consumer<TLRPC.StoryItem> consumer) {
        TLRPC.PeerStories userStoriesLocal = getStories(peerId);
        if (userStoriesLocal != null) {
            for (int i = 0; i < userStoriesLocal.stories.size(); i++) {
                if (userStoriesLocal.stories.get(i).id == storyId && !(userStoriesLocal.stories.get(i) instanceof TLRPC.TL_storyItemSkipped)) {
                    consumer.accept(userStoriesLocal.stories.get(i));
                    return;
                }
            }
        }
        long hash = peerId + storyId << 12;
        TLRPC.StoryItem storyItem = resolvedStories.get(hash);
        if (storyItem != null) {
            consumer.accept(storyItem);
            return;
        }
        TLRPC.TL_stories_getStoriesByID stories = new TLRPC.TL_stories_getStoriesByID();
        stories.id.add(storyId);
        stories.peer = MessagesController.getInstance(currentAccount).getInputPeer(peerId);
        ConnectionsManager.getInstance(currentAccount).sendRequest(stories, new RequestDelegate() {
            @Override
            public void run(TLObject res, TLRPC.TL_error error) {
                AndroidUtilities.runOnUIThread(() -> {
                    TLRPC.StoryItem storyItem = null;
                    if (res != null) {
                        TLRPC.TL_stories_stories response = (TLRPC.TL_stories_stories) res;
                        if (response.stories.size() > 0) {
                            storyItem = response.stories.get(0);
                            resolvedStories.put(hash, storyItem);
                        }
                    }
                    consumer.accept(storyItem);
                });
            }
        });
    }

    public ArrayList<TLRPC.PeerStories> getHiddenList() {
        return hiddenListStories;
    }

    public int getUnreadStoriesCount(long dialogId) {
        TLRPC.PeerStories userStories = allStoriesMap.get(dialogId);
        for (int i = 0; i < userStories.stories.size(); i++) {
            if (userStories.max_read_id < userStories.stories.get(i).id) {
                return  userStories.stories.size() - i;
            }
        }
        return 0;
    }

    public int getTotalStoriesCount(boolean hidden) {
        if (hidden) {
            return hasMoreHidden ? Math.max(1, totalStoriesCountHidden) : hiddenListStories.size();
        } else {
            return hasMore ? Math.max(1, totalStoriesCount) : dialogListStories.size();
        }
    }

    public void putStories(long dialogId, TLRPC.PeerStories stories) {
        allStoriesMap.put(dialogId, stories);
        if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (isContactOrService(user) || user.self) {
                storiesStorage.putPeerStories(stories);
                applyToList(stories);
            }
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (ChatObject.isInChat(chat)) {
                storiesStorage.putPeerStories(stories);
                applyToList(stories);
            }
        }
    }

    public void setLoading(long dialogId, boolean loading) {
        if (loading) {
            loadingDialogsStories.put(dialogId, 1);
        } else {
            loadingDialogsStories.delete(dialogId);
        }
    }

    public boolean isLoading(long dialogId) {
        return loadingDialogsStories.get(dialogId, 0) == 1;
    }

    public void removeContact(long dialogId) {
        for (int i = 0; i < dialogListStories.size(); i++) {
            if (DialogObject.getPeerDialogId(dialogListStories.get(i).peer) == dialogId) {
                dialogListStories.remove(i);
                break;
            }
        }
        for (int i = 0; i < hiddenListStories.size(); i++) {
            if (DialogObject.getPeerDialogId(hiddenListStories.get(i).peer) == dialogId) {
                hiddenListStories.remove(i);
                break;
            }
        }
        storiesStorage.deleteAllUserStories(dialogId);
        MessagesController.getInstance(currentAccount).checkArchiveFolder();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }

    public StoriesStorage getStoriesStorage() {
        return storiesStorage;
    }

    public boolean hasHiddenStories() {
        return !hiddenListStories.isEmpty();
    }

    public void checkExpiredStories() {
        checkExpireStories(dialogListStories);
        checkExpireStories(hiddenListStories);
    }

    private void checkExpireStories(ArrayList<TLRPC.PeerStories> dialogListStories) {
        boolean notify = false;
        for (int k = 0; k < dialogListStories.size(); k++) {
            TLRPC.PeerStories stories = dialogListStories.get(k);
            long dialogId = DialogObject.getPeerDialogId(stories.peer);
            for (int i = 0; i < stories.stories.size(); i++) {
                if (StoriesUtilities.isExpired(currentAccount, stories.stories.get(i))) {
                    stories.stories.remove(i);
                    i--;
                }
            }
            if (stories.stories.isEmpty() && !hasUploadingStories(dialogId)) {
                allStoriesMap.remove(dialogId);
                dialogListStories.remove(stories);
                notify = true;
            }
        }
        if (notify) {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
        }
    }

    public void checkExpiredStories(long dialogId) {
        TLRPC.PeerStories userStories = getStories(dialogId);
        if (userStories == null) {
            return;
        }
        for (int i = 0; i < userStories.stories.size(); i++) {
            if (StoriesUtilities.isExpired(currentAccount, userStories.stories.get(i))) {
                userStories.stories.remove(i);
                i--;
            }
        }
        if (userStories.stories.isEmpty() && !hasUnreadStories(dialogId)) {
            dialogListStories.remove(userStories);
            hiddenListStories.remove(userStories);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
        }
    }

    public boolean hasLoadingStories() {
        return loadingDialogsStories.size() > 0;
    }

    public TLRPC.TL_storiesStealthMode getStealthMode() {
        return stealthMode;
    }

    public void setStealthMode(TLRPC.TL_storiesStealthMode stealthMode) {
        this.stealthMode = stealthMode;
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.stealthModeChanged);
        writeStealthMode(stealthMode);
    }

    public void setStoryReaction(long dialogId, TLRPC.StoryItem storyItem, ReactionsLayoutInBubble.VisibleReaction visibleReaction) {
        if (storyItem == null) {
            return;
        }
        TLRPC.TL_stories_sendReaction req = new TLRPC.TL_stories_sendReaction();
        req.story_id = storyItem.id;
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        if (visibleReaction == null) {
            req.reaction = new TLRPC.TL_reactionEmpty();
           // req.flags |= 1;
            storyItem.flags &= ~32768;
            storyItem.sent_reaction = null;
        } else if (visibleReaction.documentId != 0) {
            TLRPC.TL_reactionCustomEmoji reactionCustomEmoji = new TLRPC.TL_reactionCustomEmoji();
            reactionCustomEmoji.document_id = visibleReaction.documentId;
            req.reaction = reactionCustomEmoji;
           // req.flags |= 1;
            storyItem.flags |= 32768;
            storyItem.sent_reaction = reactionCustomEmoji;
        } else if (visibleReaction.emojicon != null) {
            TLRPC.TL_reactionEmoji reactionEmoji = new TLRPC.TL_reactionEmoji();
            reactionEmoji.emoticon = visibleReaction.emojicon;
            req.reaction = reactionEmoji;
            storyItem.flags |= 32768;
            storyItem.sent_reaction = reactionEmoji;
        }
        updateStoryItem(dialogId, storyItem);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {

        });
    }

    public void updateStoryReaction(long dialogId, int storyId, TLRPC.Reaction reaction) {
        TLRPC.StoryItem storyItem = findStory(dialogId, storyId);
        if (storyItem != null) {
            storyItem.sent_reaction = reaction;
            if (storyItem.sent_reaction != null) {
                storyItem.flags |= 32768;
            } else {
                storyItem.flags &= ~32768;
            }
            updateStoryItem(dialogId, storyItem);
        }
    }

    private TLRPC.StoryItem findStory(long dialogId, int storyId) {
        TLRPC.PeerStories stories = allStoriesMap.get(dialogId);
        if (stories != null) {
            for (int i = 0; i < stories.stories.size(); i++) {
                if (stories.stories.get(i).id == storyId) {
                    return stories.stories.get(i);
                }
            }
        }
        return null;
    }

    public void onPremiumChanged() {
        selfViewsModel.clear();
    }

    public class UploadingStory implements NotificationCenter.NotificationCenterDelegate {

        public final long random_id;
        public final boolean edit;

        final StoryEntry entry;
        private boolean entryDestroyed;
        String path;
        String firstFramePath;
        float progress;
        float convertingProgress, uploadProgress;
        boolean ready;
        boolean isVideo;

        boolean canceled;
        private int currentRequest;
        private long firstSecondSize = -1;
        private long duration;

        private MessageObject messageObject;
        private VideoEditedInfo info;
        private boolean putMessages;
        private boolean isCloseFriends;

        public boolean hadFailed;
        public boolean failed;
        long dialogId;

        public UploadingStory(StoryEntry entry) {
            this.entry = entry;
            random_id = Utilities.random.nextLong();
            edit = entry.isEdit;
            if (entry.uploadThumbFile != null) {
                this.firstFramePath = entry.uploadThumbFile.getAbsolutePath();
            }
            failed = hadFailed = entry.isError;

            if (entry.isEdit) {
                dialogId = entry.editStoryPeerId;
            } else {
                if (entry.peer == null || entry.peer instanceof TLRPC.TL_inputPeerSelf) {
                    dialogId = UserConfig.getInstance(currentAccount).clientUserId;
                } else {
                    dialogId = DialogObject.getPeerDialogId(entry.peer);
                }
            }
        }

        private void startForeground() {
            Intent intent = new Intent(ApplicationLoader.applicationContext, StoryUploadingService.class);
            intent.putExtra("path", path);
            intent.putExtra("currentAccount", currentAccount);
            try {
                ApplicationLoader.applicationContext.startService(intent);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        public void start() {
            if (entry.isEdit && !entry.editedMedia) {
                sendUploadedRequest(null);
                return;
            }
            isCloseFriends = entry.privacy != null && entry.privacy.isCloseFriends();
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploaded);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploadFailed);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploadProgressChanged);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.filePreparingFailed);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.filePreparingStarted);
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileNewChunkAvailable);
            if (isVideo = entry.wouldBeVideo()) {
                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = 1;
                path = message.attachPath = StoryEntry.makeCacheFile(currentAccount, true).getAbsolutePath();
                messageObject = new MessageObject(currentAccount, message, (MessageObject) null, false, false);
                entry.getVideoEditedInfo(info -> {
                    this.info = info;
                    messageObject.videoEditedInfo = info;
                    duration = info.estimatedDuration / 1000L;
                    if (messageObject.videoEditedInfo.needConvert()) {
                        MediaController.getInstance().scheduleVideoConvert(messageObject, false, false);
                    } else {
                        boolean rename = new File(messageObject.videoEditedInfo.originalPath).renameTo(new File(path));
                        if (rename) {
                            FileLoader.getInstance(currentAccount).uploadFile(path, false, false, ConnectionsManager.FileTypeVideo);
                        }
                    }
                });
            } else {
                final File destFile = StoryEntry.makeCacheFile(currentAccount, false);
                path = destFile.getAbsolutePath();
                Utilities.themeQueue.postRunnable(() -> {
                    entry.buildPhoto(destFile);
                    AndroidUtilities.runOnUIThread(() -> {
                        ready = true;
                        upload();
                    });
                });
            }
            startForeground();
        }

        public void tryAgain() {
            failed = false;
            entryDestroyed = false;
            progress = 0;
            uploadProgress = 0;
            convertingProgress = 0;
            if (path != null) {
                try {
                    new File(path).delete();
                    path = null;
                } catch (Exception ignore) {}
            }
            start();
        }

        private void upload() {
            if (entry.shareUserIds != null) {
                putMessages();
            } else {
                FileLoader.getInstance(currentAccount).uploadFile(path, false, !entry.isVideo, isVideo ? Math.max(1, (int) (info != null ? info.estimatedSize : 0)) : 0, entry.isVideo ? ConnectionsManager.FileTypeVideo : ConnectionsManager.FileTypePhoto, true);
            }
        }

        public void cleanup() {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploaded);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploadFailed);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploadProgressChanged);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.filePreparingFailed);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.filePreparingStarted);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileNewChunkAvailable);
            if (!failed) {
                ArrayList<UploadingStory> list = uploadingStoriesByDialogId.get(dialogId);
                if (list != null) {
                    list.remove(UploadingStory.this);
                }
            }
            ArrayList<UploadingStory> list = uploadingAndEditingStories.get(dialogId);
            if (list != null) {
                list.remove(UploadingStory.this);
            }
            if (edit) {
                HashMap<Integer, UploadingStory> map = editingStories.get(dialogId);
                if (map != null) {
                    map.remove(entry.editStoryId);
                }
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
            if (entry != null && !entry.isEditSaved && !entryDestroyed) {
                entry.destroy(false);
                entryDestroyed = true;
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.uploadStoryEnd, path);
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.filePreparingStarted) {
                if (args[0] == messageObject) {
                    this.path = (String) args[1];
                    upload();
                }
            } else if (id == NotificationCenter.fileNewChunkAvailable) {
                if (args[0] == messageObject) {
                    String finalPath = (String) args[1];
                    long availableSize = (Long) args[2];
                    long finalSize = (Long) args[3];
                    convertingProgress = (float) args[4];
                    progress = convertingProgress * .3f + uploadProgress * .7f;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.uploadStoryProgress, path, progress);

                    if (firstSecondSize < 0 && convertingProgress * duration >= 1000) {
                        firstSecondSize = availableSize;
                    }

                    FileLoader.getInstance(currentAccount).checkUploadNewDataAvailable(finalPath, false, Math.max(1, availableSize), finalSize, convertingProgress);

                    if (finalSize > 0) {
                        if (firstSecondSize < 0) {
                            firstSecondSize = finalSize;
                        }
                        ready = true;
                    }
                }
            } else if (id == NotificationCenter.filePreparingFailed) {
                if (args[0] == messageObject) {
                    if (!edit) {
                        entry.isError = true;
                        entry.error = new TLRPC.TL_error();
                        entry.error.code = 400;
                        entry.error.text = "FILE_PREPARE_FAILED";
                        entryDestroyed = true;
                        hadFailed = failed = true;
                        getDraftsController().edit(entry);
                    }
                    cleanup();
                }
            } else if (id == NotificationCenter.fileUploaded) {
                String location = (String) args[0];
                if (path != null && location.equals(path)) {
                    TLRPC.InputFile uploadedFile = (TLRPC.InputFile) args[1];
                    sendUploadedRequest(uploadedFile);
                }
            } else if (id == NotificationCenter.fileUploadFailed) {
                String location = (String) args[0];
                if (path != null && location.equals(path)) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, LocaleController.getString("StoryUploadError", R.string.StoryUploadError));
                    cleanup();
                }
            } else if (id == NotificationCenter.fileUploadProgressChanged) {
                String location = (String) args[0];
                if (location.equals(path)) {
                    Long loadedSize = (Long) args[1];
                    Long totalSize = (Long) args[2];
                    uploadProgress = Math.min(1f, loadedSize / (float) totalSize);
                    progress = convertingProgress * .3f + uploadProgress * .7f;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.uploadStoryProgress, path, progress);
                }
            }
        }

        private void sendUploadedRequest(TLRPC.InputFile uploadedFile) {
            if (canceled) {
                return;
            }
            if (entry.shareUserIds != null) {
                return;
            }

            TLRPC.InputMedia media = null;
            if (uploadedFile != null) {
                if (entry.wouldBeVideo()) {
                    TLRPC.TL_inputMediaUploadedDocument inputMediaVideo = new TLRPC.TL_inputMediaUploadedDocument();
                    inputMediaVideo.file = uploadedFile;
                    TLRPC.TL_documentAttributeVideo attributeVideo = new TLRPC.TL_documentAttributeVideo();
                    SendMessagesHelper.fillVideoAttribute(path, attributeVideo, null);
                    attributeVideo.supports_streaming = true;
                    attributeVideo.flags |= 4;
                    attributeVideo.preload_prefix_size = (int) firstSecondSize;
                    inputMediaVideo.attributes.add(attributeVideo);
                    if (entry.stickers != null && (!entry.stickers.isEmpty() || entry.editStickers != null && !entry.editStickers.isEmpty())) {
                        inputMediaVideo.flags |= 1;
                        inputMediaVideo.stickers = new ArrayList<>(entry.stickers);
                        if (entry.editStickers != null) {
                            inputMediaVideo.stickers.addAll(entry.editStickers);
                        }
                        inputMediaVideo.attributes.add(new TLRPC.TL_documentAttributeHasStickers());
                    }
                    media = inputMediaVideo;
                    media.nosound_video = entry.audioPath == null && (entry.muted || !entry.isVideo);
                    media.mime_type = "video/mp4";
                } else {
                    TLRPC.TL_inputMediaUploadedPhoto inputMediaPhoto = new TLRPC.TL_inputMediaUploadedPhoto();
                    inputMediaPhoto.file = uploadedFile;
                    media = inputMediaPhoto;
                    MimeTypeMap myMime = MimeTypeMap.getSingleton();
                    String ext = "txt";
                    int idx = path.lastIndexOf('.');
                    if (idx != -1) {
                        ext = path.substring(idx + 1).toLowerCase();
                    }
                    String mimeType = myMime.getMimeTypeFromExtension(ext);
                    media.mime_type = mimeType;
                    if (entry.stickers != null && (!entry.stickers.isEmpty() || entry.editStickers != null && !entry.editStickers.isEmpty())) {
                        inputMediaPhoto.flags |= 1;
                        if (entry.editStickers != null) {
                            inputMediaPhoto.stickers.addAll(entry.editStickers);
                        }
                        inputMediaPhoto.stickers = new ArrayList<>(entry.stickers);
                    }
                }
            }
            TLObject req;

            final int captionLimit = UserConfig.getInstance(currentAccount).isPremium() ? MessagesController.getInstance(currentAccount).storyCaptionLengthLimitPremium : MessagesController.getInstance(currentAccount).storyCaptionLengthLimitDefault;
            if (edit) {
                TLRPC.TL_stories_editStory editStory = new TLRPC.TL_stories_editStory();
                editStory.id = entry.editStoryId;
                editStory.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);

                if (media != null && entry.editedMedia) {
                    editStory.flags |= 1;
                    editStory.media = media;
                }

                if (entry.editedCaption && entry.caption != null) {
                    editStory.flags |= 2;
                    CharSequence[] caption = new CharSequence[]{ entry.caption };
                    if (caption[0].length() > captionLimit) {
                        caption[0] = caption[0].subSequence(0, captionLimit);
                    }
                    if (MessagesController.getInstance(currentAccount).storyEntitiesAllowed()) {
                        editStory.entities = MediaDataController.getInstance(currentAccount).getEntities(caption, true);
                    } else {
                        editStory.entities.clear();
                    }
                    if (caption[0].length() > captionLimit) {
                        caption[0] = caption[0].subSequence(0, captionLimit);
                    }
                    editStory.caption = caption[0].toString();
                }

                if (entry.editedPrivacy) {
                    editStory.flags |= 4;
                    editStory.privacy_rules.addAll(entry.privacyRules);
                }

                if (entry.editedMediaAreas != null) {
                    editStory.media_areas.addAll(entry.editedMediaAreas);
                }
                if (entry.mediaEntities != null) {
                    for (int i = 0; i < entry.mediaEntities.size(); ++i) {
                        VideoEditedInfo.MediaEntity mediaEntity = entry.mediaEntities.get(i);
                        if (mediaEntity.mediaArea != null) {
                            editStory.media_areas.add(mediaEntity.mediaArea);
                        }
                    }
                }
                if (!editStory.media_areas.isEmpty()) {
                    editStory.flags |= 8;
                }

                req = editStory;
            } else {
                TLRPC.TL_stories_sendStory sendStory = new TLRPC.TL_stories_sendStory();
                sendStory.random_id = random_id;
                sendStory.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                sendStory.media = media;
                sendStory.privacy_rules.addAll(entry.privacyRules);
                sendStory.pinned = entry.pinned;
                sendStory.noforwards = !entry.allowScreenshots;

                if (entry.caption != null) {
                    sendStory.flags |= 3;
                    CharSequence[] caption = new CharSequence[]{ entry.caption };
                    if (caption[0].length() > captionLimit) {
                        caption[0] = caption[0].subSequence(0, captionLimit);
                    }
                    if (MessagesController.getInstance(currentAccount).storyEntitiesAllowed()) {
                        sendStory.entities = MediaDataController.getInstance(currentAccount).getEntities(caption, true);
                    } else {
                        sendStory.entities.clear();
                    }
                    if (caption[0].length() > captionLimit) {
                        caption[0] = caption[0].subSequence(0, captionLimit);
                    }
                    sendStory.caption = caption[0].toString();
                }

                if (entry.period == Integer.MAX_VALUE) {
                    sendStory.pinned = true;
                } else {
                    sendStory.flags |= 8;
                    sendStory.period = entry.period;
                }

                if (entry.mediaEntities != null) {
                    for (int i = 0; i < entry.mediaEntities.size(); ++i) {
                        VideoEditedInfo.MediaEntity mediaEntity = entry.mediaEntities.get(i);
                        if (mediaEntity.mediaArea != null) {
                            sendStory.media_areas.add(mediaEntity.mediaArea);
                        }
                    }
                    if (!sendStory.media_areas.isEmpty()) {
                        sendStory.flags |= 32;
                    }
                }

                req = sendStory;
            }

            final RequestDelegate requestDelegate = (response, error) -> {
                if (response != null) {
                    failed = false;
                    TLRPC.Updates updates = (TLRPC.Updates) response;
                    int storyId = 0;
                    TLRPC.StoryItem storyItem = null;
                    for (int i = 0; i < updates.updates.size(); i++) {
                        if (updates.updates.get(i) instanceof TLRPC.TL_updateStory) {
                            TLRPC.TL_updateStory updateStory = (TLRPC.TL_updateStory) updates.updates.get(i);
                            updateStory.story.attachPath = path;
                            updateStory.story.firstFramePath = firstFramePath;
                            updateStory.story.justUploaded = !edit;
                            storyId = updateStory.story.id;
                            if (storyItem == null) {
                                storyItem = updateStory.story;
                            } else {
                                storyItem.media = updateStory.story.media;
                            }
                        }
                        if (updates.updates.get(i) instanceof TLRPC.TL_updateStoryID) {
                            TLRPC.TL_updateStoryID updateStory = (TLRPC.TL_updateStoryID) updates.updates.get(i);
                            if (storyItem == null) {
                                storyItem = new TLRPC.TL_storyItem();
                                storyItem.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
                                storyItem.expire_date = storyItem.date + (entry.period == Integer.MAX_VALUE ? 86400 : entry.period);
                                storyItem.parsedPrivacy = null;
                                storyItem.privacy = StoryPrivacyBottomSheet.StoryPrivacy.toOutput(entry.privacyRules);
                                storyItem.pinned = entry.period == Integer.MAX_VALUE;
                                storyItem.dialogId = UserConfig.getInstance(currentAccount).clientUserId;
                                storyItem.attachPath = path;
                                storyItem.firstFramePath = firstFramePath;
                                storyItem.id = updateStory.id;
                                storyItem.justUploaded = !edit;
                            }
                        }
                    }
                    final long did = dialogId;
                    if (canceled) {
                        TLRPC.TL_stories_deleteStories stories_deleteStory = new TLRPC.TL_stories_deleteStories();
                        stories_deleteStory.id.add(storyId);
                        ConnectionsManager.getInstance(currentAccount).sendRequest(stories_deleteStory, (response1, error1) -> {
                            AndroidUtilities.runOnUIThread(StoriesController.this::invalidateStoryLimit);
                        });
                    } else {
                        if ((storyId == 0 || edit) && storyItem != null) {
                            TLRPC.TL_updateStory tl_updateStory = new TLRPC.TL_updateStory();
                            tl_updateStory.peer = MessagesController.getInstance(currentAccount).getPeer(did);
                            tl_updateStory.story = storyItem;
                            AndroidUtilities.runOnUIThread(() -> {
                                MessagesController.getInstance(currentAccount).getStoriesController().processUpdate(tl_updateStory);
                            });
                        }
                        final TLRPC.StoryItem storyItemFinal = storyItem;
                        if (storyItemFinal.media != null && storyItemFinal.attachPath != null) {
                            if (storyItemFinal.media.document != null) {
                                FileLoader.getInstance(currentAccount).setLocalPathTo(storyItemFinal.media.document, storyItemFinal.attachPath);
                            } else if (storyItemFinal.media.photo != null) {
                                TLRPC.Photo photo = storyItemFinal.media.photo;
                                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE);
                                FileLoader.getInstance(currentAccount).setLocalPathTo(size, storyItemFinal.attachPath);
                            }
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            entryDestroyed = true;
                            if (entry.isError) {
                                getDraftsController().delete(entry);
                            }
                            entry.isError = false;
                            entry.error = null;
                            getDraftsController().saveForEdit(entry, did, storyItemFinal);
                            if (!edit) {
                                invalidateStoryLimit();
                            }
                        });
                        MessagesController.getInstance(currentAccount).processUpdateArray(updates.updates, updates.users, updates.chats, false, updates.date);
                    }
                } else if (error != null && !edit) {
                    AndroidUtilities.runOnUIThread(() -> {
                        entry.isError = true;
                        if (checkStoryError(error)) {
                            entry.error = null;
                        } else {
                            entry.error = error;
                        }
                        entryDestroyed = true;
                        hadFailed = failed = true;
                        getDraftsController().edit(entry);
                    });
                }

                AndroidUtilities.runOnUIThread(this::cleanup);
            };

            if (BuildVars.DEBUG_PRIVATE_VERSION && !edit && entry.caption != null && entry.caption.toString().contains("#failtest") && !hadFailed) {
                TLRPC.TL_error error = new TLRPC.TL_error();
                error.code = 400;
                error.text = "FORCED_TO_FAIL";
                requestDelegate.run(null, error);
            } else {
                currentRequest = ConnectionsManager.getInstance(currentAccount).sendRequest(req, requestDelegate);
            }
        }

        private void putMessages() {
            if (entry.shareUserIds == null || putMessages) {
                return;
            }
            final int count = entry.shareUserIds.size();
            String caption = entry.caption == null ? null : entry.caption.toString();
            ArrayList<TLRPC.MessageEntity> captionEntities = entry.caption == null ? null : MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{entry.caption}, true);
            for (int i = 0; i < count; ++i) {
                long userId = entry.shareUserIds.get(i);
                if (entry.wouldBeVideo()) {
                    SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(currentAccount), path, null, userId, null, null, null, captionEntities, 0, null, !entry.silent, entry.scheduleDate, false, false, caption);
                } else {
                    SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(currentAccount), path, null, null, userId, null, null, null, captionEntities, null, null, 0, null, null, !entry.silent, entry.scheduleDate, false, caption  /* TODO: */);
                }
            }
            putMessages = true;
        }

        public void cancel() {
            if (failed) {
                getDraftsController().delete(entry);
                uploadingStoriesByDialogId.get(dialogId).remove(UploadingStory.this);
            }
            canceled = true;
            if (entry.wouldBeVideo()) {
                MediaController.getInstance().cancelVideoConvert(messageObject);
            }
            FileLoader.getInstance(currentAccount).cancelFileUpload(path, false);
            if (currentRequest >= 0) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(currentRequest, true);
            }
            cleanup();
        }

        public boolean isCloseFriends() {
            return isCloseFriends;
        }
    }

    private final HashMap<Long, StoriesList>[] storiesLists = new HashMap[2];

    @Nullable
    public StoriesList getStoriesList(long dialogId, int type) {
        return getStoriesList(dialogId, type, true);
    }

    @Nullable
    private StoriesList getStoriesList(long dialogId, int type, boolean createIfNotExist) {
        if (storiesLists[type] == null) {
            storiesLists[type] = new HashMap<>();
        }
        StoriesList list = storiesLists[type].get(dialogId);
        if (list == null && createIfNotExist) {
            storiesLists[type].put(dialogId, list = new StoriesList(currentAccount, dialogId, type, this::destroyStoryList));
        }
        return list;
    }

    private static String storyItemIds(List<TLRPC.StoryItem> storyItems) {
        try {
            if (storyItems == null) {
                return "null";
            }
            String s = "";
            for (int i = 0; i < storyItems.size(); ++i) {
                if (i > 0) s += ", ";
                s += storyItems.get(i).id + "@" + storyItems.get(i).dialogId;
            }
            return s;
        } catch (Exception e) {
            return "err";
        }
    }

    private static String storyItemMessageIds(List<MessageObject> storyItems) {
        try {
            if (storyItems == null) {
                return "null";
            }
            String s = "";
            for (int i = 0; i < storyItems.size(); ++i) {
                if (i > 0) s += ", ";
                TLRPC.StoryItem storyItem = storyItems.get(i).storyItem;
                if (storyItem == null) {
                    s += "null";
                } else
                    s += storyItem.id + "@" + storyItem.dialogId;
            }
            return s;
        } catch (Exception e) {
            return "err";
        }
    }

    public void updateStoriesInLists(long dialogId, List<TLRPC.StoryItem> storyItems) {
        FileLog.d("updateStoriesInLists " + dialogId + " storyItems[" + storyItems.size() + "] {" + storyItemIds(storyItems) + "}");
        StoriesList pinned = getStoriesList(dialogId, StoriesList.TYPE_PINNED, false);
        StoriesList archived = getStoriesList(dialogId, StoriesList.TYPE_ARCHIVE, false);
        if (pinned != null) {
            pinned.updateStories(storyItems);
        }
        if (archived != null) {
            archived.updateStories(storyItems);
        }
    }

    public void updateDeletedStoriesInLists(long dialogId, List<TLRPC.StoryItem> storyItems) {
        FileLog.d("updateDeletedStoriesInLists " + dialogId + " storyItems[" + storyItems.size() + "] {" + storyItemIds(storyItems) + "}");
        StoriesList pinned = getStoriesList(dialogId, StoriesList.TYPE_PINNED, false);
        StoriesList archived = getStoriesList(dialogId, StoriesList.TYPE_ARCHIVE, false);
        if (pinned != null) {
            pinned.updateDeletedStories(storyItems);
        }
        if (archived != null) {
            archived.updateDeletedStories(storyItems);
        }
    }

    public void destroyStoryList(StoriesList list) {
        if (storiesLists[list.type] != null) {
            storiesLists[list.type].remove(list.dialogId);
        }
    }

    public static class StoriesList {

        private static HashMap<Integer, Long> lastLoadTime;

        private int maxLinkId = 0;
        private final ArrayList<Integer> links = new ArrayList<>();
        public int link() {
            final int id = maxLinkId++;
            links.add(id);
            AndroidUtilities.cancelRunOnUIThread(destroyRunnable);
            return id;
        }
        public void unlink(int id) {
            links.remove((Integer) id);
            if (links.isEmpty()) {
                AndroidUtilities.cancelRunOnUIThread(destroyRunnable);
                AndroidUtilities.runOnUIThread(destroyRunnable, 1000 * 60 * 5);
            }
        }

        public static final int TYPE_PINNED = 0;
        public static final int TYPE_ARCHIVE = 1;

        public final int currentAccount;
        public final long dialogId;
        public final int type;

        public final HashMap<Long, TreeSet<Integer>> groupedByDay = new HashMap<>();

        public final ArrayList<MessageObject> messageObjects = new ArrayList<>();
        private final HashMap<Integer, MessageObject> messageObjectsMap = new HashMap<>();

        private final SortedSet<Integer> cachedObjects = new TreeSet<>(Comparator.reverseOrder());
        private final SortedSet<Integer> loadedObjects = new TreeSet<>(Comparator.reverseOrder());

        private boolean showPhotos = true;
        private boolean showVideos = true;

        public void updateFilters(boolean photos, boolean videos) {
            this.showPhotos = photos;
            this.showVideos = videos;
            this.fill(true);
        }

        public boolean isOnlyCache() {
            return loadedObjects.isEmpty() && canLoad();
        }

        public boolean showPhotos() {
            return showPhotos;
        }

        public boolean showVideos() {
            return showVideos;
        }

        private final ArrayList<MessageObject> tempArr = new ArrayList<>();

        private final Runnable notify = () -> {
            NotificationCenter.getInstance(StoriesList.this.currentAccount).postNotificationName(NotificationCenter.storiesListUpdated, StoriesList.this);
        };

        public void fill(boolean notify) {
            fill(this.messageObjects, showPhotos, showVideos);
            if (notify) {
                AndroidUtilities.cancelRunOnUIThread(this.notify);
                AndroidUtilities.runOnUIThread(this.notify);
            }
        }

        private void fill(ArrayList<MessageObject> arrayList, boolean showPhotos, boolean showVideos) {
            tempArr.clear();
            int minId = Integer.MAX_VALUE;
            for (int id : loadedObjects) {
                MessageObject msg = messageObjectsMap.get(id);
                if (filter(msg, showPhotos, showVideos)) {
                    tempArr.add(msg);
                }
                if (id < minId) {
                    minId = id;
                }
            }
            if (!done) {
                Iterator<Integer> i = cachedObjects.iterator();
                while (i.hasNext() && (totalCount == -1 || tempArr.size() < totalCount)) {
                    int id = i.next();
                    if (minId == Integer.MAX_VALUE || id < minId) {
                        MessageObject msg = messageObjectsMap.get(id);
                        if (filter(msg, showPhotos, showVideos)) {
                            tempArr.add(msg);
                        }
                    }
                }
            }
            arrayList.clear();
            arrayList.addAll(tempArr);
        }

        private boolean filter(MessageObject msg, boolean photos, boolean videos) {
            return msg != null && msg.isStory() && (photos && msg.isPhoto() || videos && msg.isVideo() || msg.storyItem.media instanceof TLRPC.TL_messageMediaUnsupported);
        }

        private boolean done;
        private int totalCount = -1;
        private boolean preloading, loading;
        private boolean invalidateAfterPreload;
        private boolean error;
        private Runnable destroyRunnable;

        private Utilities.CallbackReturn<Integer, Boolean> toLoad;

        private StoriesList(int currentAccount, long dialogId, int type, Utilities.Callback<StoriesList> destroy) {
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
            this.type = type;
            this.destroyRunnable = () -> destroy.run(this);

            preloadCache();
        }

        private void preloadCache() {
            if (preloading || loading || error) {
                return;
            }

            preloading = true;
            final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            storage.getStorageQueue().postRunnable(() -> {
                SQLiteCursor cursor = null;
                HashSet<Long> loadUserIds = new HashSet<>();
                ArrayList<MessageObject> cacheResult = new ArrayList<>();
                final ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
                try {
                    SQLiteDatabase database = storage.getDatabase();
                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM profile_stories WHERE dialog_id = %d AND type = %d ORDER BY story_id DESC", dialogId, type));
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            TLRPC.StoryItem storyItem = TLRPC.StoryItem.TLdeserialize(data, data.readInt32(true), true);
                            storyItem.dialogId = dialogId;
                            storyItem.messageId = storyItem.id;
                            MessageObject msg = new MessageObject(currentAccount, storyItem);
                            for (TLRPC.PrivacyRule rule : storyItem.privacy) {
                                if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                                    loadUserIds.addAll(((TLRPC.TL_privacyValueDisallowUsers) rule).users);
                                } else if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                                    loadUserIds.addAll(((TLRPC.TL_privacyValueAllowUsers) rule).users);
                                }
                            }
                            msg.generateThumbs(false);
                            cacheResult.add(msg);
                            data.reuse();
                        }
                    }
                    cursor.dispose();

                    if (!loadUserIds.isEmpty()) {
                        storage.getUsersInternal(TextUtils.join(",", loadUserIds), loadedUsers);
                    }
                } catch (Throwable e) {
                    storage.checkSQLException(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                        cursor = null;
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    FileLog.d("StoriesList "+type+"{"+ dialogId +"} preloadCache {" + storyItemMessageIds(cacheResult) + "}");
                    preloading = false;
                    MessagesController.getInstance(currentAccount).putUsers(loadedUsers, true);
                    if (invalidateAfterPreload) {
                        invalidateAfterPreload = false;
                        toLoad = null;
                        invalidateCache();
                        return;
                    }

                    cachedObjects.clear();
                    for (int i = 0; i < cacheResult.size(); ++i) {
                        pushObject(cacheResult.get(i), true);
                    }
                    fill(false);

                    if (toLoad != null) {
                        toLoad.run(0);
                        toLoad = null;
                    }

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesListUpdated, StoriesList.this);
                });
            });
        }

        private void pushObject(MessageObject messageObject, boolean cachedOrLoaded) {
            if (messageObject == null) {
                return;
            }
            messageObjectsMap.put(messageObject.getId(), messageObject);
            (cachedOrLoaded ? cachedObjects : loadedObjects).add(messageObject.getId());

            long id = day(messageObject);
            TreeSet<Integer> group = groupedByDay.get(id);
            if (group == null) {
                groupedByDay.put(id, group = new TreeSet<>(Comparator.reverseOrder()));
            }
            group.add(messageObject.getId());
        }

        private boolean removeObject(int storyId, boolean removeFromCache) {
            MessageObject messageObject = messageObjectsMap.remove(storyId);
            if (removeFromCache) {
                cachedObjects.remove(storyId);
            }
            loadedObjects.remove(storyId);
            if (messageObject != null) {
                long id = day(messageObject);
                SortedSet<Integer> group = groupedByDay.get(id);
                if (group != null) {
                    group.remove(storyId);
                    if (group.isEmpty()) {
                        groupedByDay.remove(id);
                    }
                }
                return true;
            }
            return false;
        }

        public static long day(MessageObject messageObject) {
            if (messageObject == null) {
                return 0;
            }
            final long date = messageObject.messageOwner.date;
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(date * 1000L);
            final int year = calendar.get(Calendar.YEAR);
            final int month = calendar.get(Calendar.MONTH);
            final int day = calendar.get(Calendar.DAY_OF_MONTH);
            return year * 10000L + month * 100L + day;
        }

        public SortedSet<Integer> getStoryDayGroup(MessageObject messageObject) {
            return groupedByDay.get(day(messageObject));
        }

        public ArrayList<ArrayList<Integer>> getDays() {
            final ArrayList<Long> keys = new ArrayList<>(groupedByDay.keySet());
            Collections.sort(keys, (a, b) -> (int) (b - a));
            final ArrayList<ArrayList<Integer>> days = new ArrayList<>();
            for (Long key : keys) {
                TreeSet<Integer> storyIds = groupedByDay.get(key);
                if (storyIds != null) {
                    days.add(new ArrayList<>(storyIds));
                }
            }
            return days;
        }

        public void invalidateCache() {
            if (preloading) {
                invalidateAfterPreload = true;
                return;
            }

            resetCanLoad();

            final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    SQLiteDatabase database = storage.getDatabase();
                    database.executeFast(String.format(Locale.US, "DELETE FROM profile_stories WHERE dialog_id = %d AND type = %d", dialogId, type)).stepThis().dispose();
                } catch (Throwable e) {
                    storage.checkSQLException(e);
                }

                AndroidUtilities.runOnUIThread(() -> {
                    cachedObjects.clear();
                    fill(true);
                });
            });
        }

        private boolean saving;

        private void saveCache() {
            if (saving) {
                return;
            }
            saving = true;
            final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            storage.getStorageQueue().postRunnable(() -> {
                SQLitePreparedStatement state = null;
                ArrayList<MessageObject> toSave = new ArrayList<>();
                fill(toSave, true, true);
                FileLog.d("StoriesList " + type + "{"+ dialogId +"} saveCache {" + storyItemMessageIds(toSave) + "}");
                try {
                    SQLiteDatabase database = storage.getDatabase();
                    database.executeFast(String.format(Locale.US, "DELETE FROM profile_stories WHERE dialog_id = %d AND type = %d", dialogId, type)).stepThis().dispose();
                    state = database.executeFast("REPLACE INTO profile_stories VALUES(?, ?, ?, ?)");

                    for (int i = 0; i < toSave.size(); ++i) {
                        MessageObject messageObject = toSave.get(i);
                        TLRPC.StoryItem storyItem = messageObject.storyItem;
                        if (storyItem == null) {
                            continue;
                        }

                        NativeByteBuffer data = new NativeByteBuffer(storyItem.getObjectSize());
                        storyItem.serializeToStream(data);

                        state.requery();
                        state.bindLong(1, dialogId);
                        state.bindInteger(2, storyItem.id);
                        state.bindByteBuffer(3, data);
                        state.bindInteger(4, type);
                        state.step();
                        data.reuse();
                    }
                } catch (Throwable e) {
                    storage.checkSQLException(e);
                } finally {
                    if (state != null) {
                        state.dispose();
                        state = null;
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                });
            });
        }

        private boolean canLoad() {
            if (lastLoadTime == null) {
                return true;
            }
            final int key = Objects.hash(currentAccount, type, dialogId);
            Long time = lastLoadTime.get(key);
            if (time == null) {
                return true;
            }
            return System.currentTimeMillis() - time > 1000L * 60 * 2;
        }

        private void resetCanLoad() {
            if (lastLoadTime != null) {
                lastLoadTime.remove(Objects.hash(currentAccount, type, dialogId));
            }
        }

        public boolean load(boolean force, final int count) {
            if (loading || (done || error || !canLoad()) && !force) {
                return false;
            }
            if (preloading) {
                toLoad = i -> load(force, count);
                return false;
            }

            final int offset_id;
            TLObject request;
            if (type == TYPE_PINNED) {
                TLRPC.TL_stories_getPinnedStories req = new TLRPC.TL_stories_getPinnedStories();
                req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                if (!loadedObjects.isEmpty()) {
                    req.offset_id = offset_id = loadedObjects.last();
                } else {
                    offset_id = -1;
                }
                req.limit = count;
                request = req;
            } else {
                TLRPC.TL_stories_getStoriesArchive req = new TLRPC.TL_stories_getStoriesArchive();
                req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
                if (!loadedObjects.isEmpty()) {
                    req.offset_id = offset_id = loadedObjects.last();
                } else {
                    offset_id = -1;
                }
                req.limit = count;
                request = req;
            }
            FileLog.d("StoriesList " + type + "{"+ dialogId +"} load");

            loading = true;
            ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, err) -> {
                if (response instanceof TLRPC.TL_stories_stories) {
                    ArrayList<MessageObject> newMessageObjects = new ArrayList<>();
                    TLRPC.TL_stories_stories stories = (TLRPC.TL_stories_stories) response;
                    for (int i = 0; i < stories.stories.size(); ++i) {
                        TLRPC.StoryItem storyItem = stories.stories.get(i);
                        newMessageObjects.add(toMessageObject(storyItem));
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        FileLog.d("StoriesList " + type + "{"+ dialogId +"} loaded {" + storyItemMessageIds(newMessageObjects) + "}");

                        MessagesController.getInstance(currentAccount).putUsers(stories.users, false);
                        loading = false;

                        totalCount = stories.count;
                        for (int i = 0; i < newMessageObjects.size(); ++i) {
                            pushObject(newMessageObjects.get(i), false);
                        }
                        done = loadedObjects.size() >= totalCount;
                        if (!done) {
                            final int loadedFromId = offset_id == -1 ? loadedObjects.first() : offset_id;
                            final int loadedToId = !loadedObjects.isEmpty() ? loadedObjects.last() : 0;
                            Iterator<Integer> i = cachedObjects.iterator();
                            while (i.hasNext()) {
                                int cachedId = i.next();
                                if (!loadedObjects.contains(cachedId) && cachedId >= loadedFromId && cachedId <= loadedToId) {
                                    i.remove();
                                    removeObject(cachedId, false);
                                }
                            }
                        } else {
                            Iterator<Integer> i = cachedObjects.iterator();
                            while (i.hasNext()) {
                                int cachedId = i.next();
                                if (!loadedObjects.contains(cachedId)) {
                                    i.remove();
                                    removeObject(cachedId, false);
                                }
                            }
                        }
                        fill(true);

                        if (done) {
                            if (lastLoadTime == null) {
                                lastLoadTime = new HashMap<>();
                            }
                            lastLoadTime.put(Objects.hash(currentAccount, type, dialogId), System.currentTimeMillis());
                        } else {
                            resetCanLoad();
                        }

                        saveCache();
                    });
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        loading = false;
                        error = true;

                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesListUpdated, StoriesList.this, false);
                    });
                }
            });
            return true;
        }

//        public void invalidate() {
//            resetCanLoad();
//            final int wasCount = messageObjects.size();
//            messageObjectsMap.clear();
//            loadedObjects.clear();
//            cachedObjects.clear();
//            invalidateCache();
//            done = false;
//            error = false;
//            load(true, Utilities.clamp(wasCount, 50, 10));
//        }

        public void updateDeletedStories(List<TLRPC.StoryItem> storyItems) {
            FileLog.d("StoriesList " + type + "{"+ dialogId +"} updateDeletedStories {" + storyItemIds(storyItems) + "}");
            if (storyItems == null) {
                return;
            }
            boolean changed = false;
            for (int i = 0; i < storyItems.size(); ++i) {
                TLRPC.StoryItem storyItem = storyItems.get(i);
                if (storyItem == null) {
                    continue;
                }
                boolean contains = loadedObjects.contains(storyItem.id) || cachedObjects.contains(storyItem.id);
                if (contains) {
                    changed = true;
                    loadedObjects.remove(storyItem.id);
                    cachedObjects.remove(storyItem.id);
                    if (totalCount != -1) {
                        totalCount--;
                    }
                }
                removeObject(storyItem.id, true);
            }
            if (changed) {
                fill(true);
                saveCache();
            }
        }

        public void updateStories(List<TLRPC.StoryItem> storyItems) {
            FileLog.d("StoriesList " + type + "{"+ dialogId +"} updateStories {" + storyItemIds(storyItems) + "}");
            if (storyItems == null) {
                return;
            }
            boolean changed = false;
            for (int i = 0; i < storyItems.size(); ++i) {
                TLRPC.StoryItem storyItem = storyItems.get(i);
                if (storyItem == null) {
                    continue;
                }
                boolean contains = loadedObjects.contains(storyItem.id) || cachedObjects.contains(storyItem.id);
                boolean shouldContain = type == TYPE_ARCHIVE ? true : storyItem.pinned;
                if (storyItem instanceof TLRPC.TL_storyItemDeleted) {
                    shouldContain = false;
                }
                if (contains != shouldContain) {
                    changed = true;
                    if (!shouldContain) {
                        FileLog.d("StoriesList remove story " + storyItem.id);
                        removeObject(storyItem.id, true);
                        if (totalCount != -1) {
                            totalCount--;
                        }
                    } else {
                        FileLog.d("StoriesList put story " + storyItem.id);
                        pushObject(toMessageObject(storyItem), false);
                        if (totalCount != -1) {
                            totalCount++;
                        }
                    }
                } else if (contains && shouldContain) {
                    MessageObject messageObject = messageObjectsMap.get(storyItem.id);
                    if (messageObject == null || !equal(messageObject.storyItem, storyItem)) {
                        FileLog.d("StoriesList update story " + storyItem.id);
                        messageObjectsMap.put(storyItem.id, toMessageObject(storyItem));
                        changed = true;
                    }
                }
            }
            if (changed) {
                fill(true);
                saveCache();
            }
        }

        public MessageObject findMessageObject(int storyId) {
            return messageObjectsMap.get(storyId);
        }

        public boolean equal(TLRPC.StoryItem a, TLRPC.StoryItem b) {
            if (a == null && b == null) {
                return true;
            }
            if ((a == null) != (b == null)) {
                return false;
            }
            return (
                a == b ||
                a.id == b.id &&
                a.media == b.media &&
                TextUtils.equals(a.caption, b.caption)
            );
        }

        private MessageObject toMessageObject(TLRPC.StoryItem storyItem) {
            storyItem.dialogId = dialogId;
            storyItem.messageId = storyItem.id;
            MessageObject msg = new MessageObject(currentAccount, storyItem);
            msg.generateThumbs(false);
            return msg;
        }

        public boolean isLoading() {
            return preloading || loading;
        }

        public boolean isFull() {
            return done;
        }

        public boolean isError() {
            return error;
        }

        public int getLoadedCount() {
            return loadedObjects.size();
        }

        public int getCount() {
            if (showVideos && showPhotos) {
                if (totalCount < 0) {
                    return messageObjects.size();
                }
                return Math.max(messageObjects.size(), totalCount);
            } else {
                return messageObjects.size();
            }
        }
    }

    private final Comparator<TLRPC.PeerStories> peerStoriesComparator = (o1, o2) -> {
        long dialogId1 = DialogObject.getPeerDialogId(o1.peer);
        long dialogId2 = DialogObject.getPeerDialogId(o2.peer);
        boolean hasUploading1 = hasUploadingStories(dialogId1);
        boolean hasUploading2 = hasUploadingStories(dialogId2);
        boolean hasUnread1 = hasUnreadStories(dialogId1);
        boolean hasUnread2 = hasUnreadStories(dialogId2);
        if (hasUploading1 == hasUploading2) {
            if (hasUnread1 == hasUnread2) {
                int service1 = UserObject.isService(dialogId1) ? 1 : 0;
                int service2 = UserObject.isService(dialogId2) ? 1 : 0;
                if (service1 == service2) {
                    int i1 = isPremium(dialogId1) ? 1 : 0;
                    int i2 = isPremium(dialogId2) ? 1 : 0;
                    if (i1 == i2) {
                        int date1 = o1.stories.isEmpty() ? 0 : o1.stories.get(o1.stories.size() - 1).date;
                        int date2 = o2.stories.isEmpty() ? 0 : o2.stories.get(o2.stories.size() - 1).date;
                        return date2 - date1;
                    } else {
                        return i2 - i1;
                    }
                } else {
                    return service2 - service1;
                }
            } else {
                int i1 = hasUnread1 ? 1 : 0;
                int i2 = hasUnread2 ? 1 : 0;
                return i2 - i1;
            }
        } else {
            int i1 = hasUploading1 ? 1 : 0;
            int i2 = hasUploading2 ? 1 : 0;
            return i2 - i1;
        }
    };

    private boolean isPremium(long uid) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(uid);
        if (user == null) {
            return false;
        }
        return user.premium;
    }

    final Runnable sortStoriesRunnable;

    public void scheduleSort() {
        AndroidUtilities.cancelRunOnUIThread(sortStoriesRunnable);
        sortStoriesRunnable.run();
       // AndroidUtilities.runOnUIThread(sortStoriesRunnable, 2000);
    }

    public boolean hasOnlySelfStories() {
        return hasSelfStories() && (getDialogListStories().isEmpty() || (getDialogListStories().size() == 1 && DialogObject.getPeerDialogId(getDialogListStories().get(0).peer) == UserConfig.getInstance(currentAccount).clientUserId));
    }

    public void sortHiddenStories() {
        sortDialogStories(hiddenListStories);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesUpdated);
    }


    public HashSet<Long> blocklist = new HashSet<>();
    private LongSparseArray<Boolean> blockedOverride = new LongSparseArray<>();

    private int blocklistCount;
    public boolean blocklistFull = false;
    private boolean blocklistLoadingReset = false;
    private boolean blocklistLoading = false;
    private int blocklistReqId;
    private long lastBlocklistRequested = 0;

    public void loadBlocklistAtFirst() {
        if (lastBlocklistRequested == 0)
            loadBlocklist(false);
    }

    public void loadBlocklist(boolean reset) {
        if (blocklistLoading) {
            if (reset && !blocklistLoadingReset) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(blocklistReqId, true);
                blocklistReqId = 0;
                blocklistLoading = blocklistLoadingReset = false;
            } else {
                return;
            }
        }

        if (reset && (System.currentTimeMillis() - lastBlocklistRequested) < 1000 * 60 * 30) {
            return;
        }

        if (!reset && blocklistFull) {
            return;
        }

        blocklistLoading = true;
        blocklistLoadingReset = reset;
        TLRPC.TL_contacts_getBlocked req = new TLRPC.TL_contacts_getBlocked();
        req.my_stories_from = true;
        if (reset) {
            req.offset = 0;
            req.limit = 100;
            blocklistFull = false;
        } else {
            req.offset = blocklist.size();
            req.limit = 25;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_blocked) {
                TLRPC.TL_contacts_blocked res = (TLRPC.TL_contacts_blocked) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);

                blocklist.clear();
                for (TLRPC.TL_peerBlocked peer : res.blocked) {
                    long id = DialogObject.getPeerDialogId(peer.peer_id);
                    blocklist.add(id);
                }
                blocklistCount = Math.max(blocklist.size(), res.count);
                blocklistFull = true;
            } else if (response instanceof TLRPC.TL_contacts_blockedSlice) {
                TLRPC.TL_contacts_blockedSlice res = (TLRPC.TL_contacts_blockedSlice) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);

                for (TLRPC.TL_peerBlocked peer : res.blocked) {
                    long id = DialogObject.getPeerDialogId(peer.peer_id);
                    blocklist.add(id);
                }
                blocklistCount = res.count;
                blocklistFull = blocklist.size() >= blocklistCount;
            } else {
                return;
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesBlocklistUpdate);
            blocklistLoading = false;
            lastBlocklistRequested = System.currentTimeMillis();
        }));
    }

    public int getBlocklistCount() {
        return blocklistCount;
    }

    public void updateBlockedUsers(HashSet<Long> ids, Runnable done) {
        TLRPC.TL_contacts_setBlocked req = new TLRPC.TL_contacts_setBlocked();
        req.my_stories_from = true;
        req.limit = blocklist.size();
        blocklistCount -= blocklist.size();
        if (blocklistCount < 0) {
            blocklistCount = 0;
        }
        blocklist.clear();
        for (long id : ids) {
            TLRPC.InputPeer inputPeer = MessagesController.getInstance(currentAccount).getInputPeer(id);
            if (inputPeer == null || inputPeer instanceof TLRPC.TL_inputPeerEmpty) {
                continue;
            }
            blocklist.add(id);
            req.id.add(inputPeer);
        }
        blocklistCount += blocklist.size();
        req.limit = Math.max(req.limit, blocklist.size());
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (done != null) {
                done.run();
            }
        }));
    }

    public boolean isBlocked(TLRPC.TL_storyView storyView) {
        if (storyView == null) {
            return false;
        }
        if (blockedOverride.containsKey(storyView.user_id)) {
            return blockedOverride.get(storyView.user_id);
        }
        if (lastBlocklistRequested == 0) {
            return storyView.blocked_my_stories_from || storyView.blocked;
        }
        if (blocklist.contains(storyView.user_id)) {
            return true;
        }
        return storyView.blocked_my_stories_from || storyView.blocked;
    }

    public boolean isBlocked(long did) {
        if (blockedOverride.containsKey(did)) {
            return blockedOverride.get(did);
        }
        return blocklist.contains(did);
    }

    public void applyStoryViewsBlocked(TLRPC.TL_stories_storyViewsList res) {
        if (res == null || res.views == null) {
            return;
        }
        for (int i = 0; i < res.views.size(); ++i) {
            TLRPC.TL_storyView view = res.views.get(i);
            if (blockedOverride.containsKey(view.user_id)) {
                blockedOverride.put(view.user_id, view.blocked_my_stories_from);
            }
        }
    }

    public void updateBlockUser(long did, boolean block) {
        updateBlockUser(did, block, true);
    }

    public void updateBlockUser(long did, boolean block, boolean request) {
        TLRPC.InputPeer inputPeer = MessagesController.getInstance(currentAccount).getInputPeer(did);
        if (inputPeer == null || inputPeer instanceof TLRPC.TL_inputPeerEmpty) {
            return;
        }

        blockedOverride.put(did, block);
        if (blocklist.contains(did) != block) {
            if (block) {
                blocklist.add(did);
                blocklistCount++;
            } else {
                blocklist.remove(did);
                blocklistCount--;
            }
        }

        if (request) {
            TLObject req;
            if (block) {
                TLRPC.TL_contacts_block blockReq = new TLRPC.TL_contacts_block();
                blockReq.my_stories_from = true;
                blockReq.id = inputPeer;
                req = blockReq;
            } else {
                TLRPC.TL_contacts_unblock unblockReq = new TLRPC.TL_contacts_unblock();
                unblockReq.my_stories_from = true;
                unblockReq.id = inputPeer;
                req = unblockReq;
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
        }

        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesBlocklistUpdate);
    }

    private boolean storyLimitFetched;
    private StoryLimit storyLimitCached;

    public StoryLimit checkStoryLimit() {
        final int countLimit = UserConfig.getInstance(currentAccount).isPremium() ?
            MessagesController.getInstance(currentAccount).storyExpiringLimitPremium :
            MessagesController.getInstance(currentAccount).storyExpiringLimitDefault;

        if (getMyStoriesCount() >= countLimit) {
            return new StoryLimit(StoryLimit.LIMIT_COUNT, 0);
        }

        if (storyLimitFetched) {
            return storyLimitCached;
        }

        TLRPC.TL_stories_canSendStory tl_stories_canSendStory = new TLRPC.TL_stories_canSendStory();
        tl_stories_canSendStory.peer = MessagesController.getInstance(currentAccount).getInputPeer(UserConfig.getInstance(currentAccount).getClientUserId());
        ConnectionsManager.getInstance(currentAccount).sendRequest(tl_stories_canSendStory, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            storyLimitFetched = true;
            if (res instanceof TLRPC.TL_boolTrue) {
                storyLimitCached = null;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesLimitUpdate);
            } else {
                checkStoryError(err);
            }
        }), ConnectionsManager.RequestFlagDoNotWaitFloodWait);
        return null;
    }

    public void canSendStoryFor(long dialogId, Consumer<Boolean> consumer, boolean showLimitsBottomSheet, Theme.ResourcesProvider resourcesProvider) {
        TLRPC.TL_stories_canSendStory tl_stories_canSendStory = new TLRPC.TL_stories_canSendStory();
        tl_stories_canSendStory.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        ConnectionsManager.getInstance(currentAccount).sendRequest(tl_stories_canSendStory, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (err != null) {
                if (err.text.contains("BOOSTS_REQUIRED")) {
                    if (showLimitsBottomSheet) {
                        MessagesController messagesController = MessagesController.getInstance(currentAccount);
                        messagesController.getBoostsController().getBoostsStats(dialogId, boostsStatus -> {
                            if (boostsStatus == null) {
                                consumer.accept(false);
                                return;
                            }
                            BaseFragment lastFragment = LaunchActivity.getLastFragment();
                            LimitReachedBottomSheet limitReachedBottomSheet = new LimitReachedBottomSheet(lastFragment, lastFragment.getContext(), LimitReachedBottomSheet.TYPE_BOOSTS_FOR_POSTING, currentAccount, resourcesProvider);
                            limitReachedBottomSheet.setBoostsStats(boostsStatus, false);
                            limitReachedBottomSheet.setDialogId(dialogId);
                            if (canPostStories(dialogId)) {
                                limitReachedBottomSheet.showStatisticButtonInLink(() -> {
                                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                                    Bundle args = new Bundle();
                                    args.putLong("chat_id", -dialogId);
                                    args.putBoolean("is_megagroup", chat.megagroup);
                                    args.putBoolean("start_from_boosts", true);
                                    args.putBoolean("only_boosts", true);
                                    StatisticActivity fragment = new StatisticActivity(args);
                                    BaseFragment lastFragment1 = LaunchActivity.getLastFragment();
                                    if (lastFragment1 != null) {
                                        if (StoryRecorder.isVisible()) {
                                            BaseFragment.BottomSheetParams params = new BaseFragment.BottomSheetParams();
                                            params.transitionFromLeft = true;
                                            lastFragment1.showAsSheet(fragment, params);
                                        } else {
                                            lastFragment1.presentFragment(fragment);
                                        }
                                    }
                                });
                            }
                            limitReachedBottomSheet.show();
                            consumer.accept(false);
                        });
                    } else {
                        consumer.accept(false);
                    }
                } else {
                    BulletinFactory bulletinFactory = BulletinFactory.global();
                    if (bulletinFactory != null) {
                        bulletinFactory.createErrorBulletin(err.text);
                    }
                    consumer.accept(false);
                }
            } else {
                consumer.accept(true);
            }
        }), ConnectionsManager.RequestFlagDoNotWaitFloodWait);
    }

    public boolean checkStoryError(TLRPC.TL_error err) {
        boolean limitUpdate = false;
        if (err != null && err.text != null) {
            if (err.text.startsWith("STORY_SEND_FLOOD_WEEKLY_")) {
                long until = 0;
                try {
                    until = Long.parseLong(err.text.substring("STORY_SEND_FLOOD_WEEKLY_".length()));
                } catch (Exception ignore) {}
                storyLimitCached = new StoryLimit(StoryLimit.LIMIT_WEEK, until);
                limitUpdate = true;
            } else if (err.text.startsWith("STORY_SEND_FLOOD_MONTHLY_")) {
                long until = 0;
                try {
                    until = Long.parseLong(err.text.substring("STORY_SEND_FLOOD_MONTHLY_".length()));
                } catch (Exception ignore) {}
                storyLimitCached = new StoryLimit(StoryLimit.LIMIT_MONTH, until);
                limitUpdate = true;
            } else if (err.text.equals("STORIES_TOO_MUCH")) {
                storyLimitCached = new StoryLimit(StoryLimit.LIMIT_COUNT, 0);
                limitUpdate = true;
            } else if (err.text.equals("PREMIUM_ACCOUNT_REQUIRED")) {
                MessagesController mc = MessagesController.getInstance(currentAccount);
                if ("enabled".equals(mc.storiesPosting)) {
                    mc.getMainSettings().edit().putString("storiesPosting", mc.storiesPosting = "premium").apply();
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesEnabledUpdate);
                }
                limitUpdate = true;
            }
        }
        if (limitUpdate) {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesLimitUpdate);
        }
        return limitUpdate;
    }

    public boolean hasStoryLimit() {
        StoryLimit storyLimit = checkStoryLimit();
        return storyLimit != null && storyLimit.active(currentAccount);
    }

    public void invalidateStoryLimit() {
        storyLimitFetched = false;
        storyLimitCached = null;
    }

    public static class StoryLimit {

        public static final int LIMIT_COUNT = 1;
        public static final int LIMIT_WEEK = 2;
        public static final int LIMIT_MONTH = 3;

        public int type;
        public long until;

        public StoryLimit(int type, long until) {
            this.type = type;
            this.until = until;
        }

        public int getLimitReachedType() {
            switch (type) {
                case LIMIT_WEEK:
                    return LimitReachedBottomSheet.TYPE_STORIES_WEEK;
                case LIMIT_MONTH:
                    return LimitReachedBottomSheet.TYPE_STORIES_MONTH;
                default:
                case LIMIT_COUNT:
                    return LimitReachedBottomSheet.TYPE_STORIES_COUNT;
            }
        }

        public boolean active(int currentAccount) {
            switch (type) {
                case LIMIT_WEEK:
                case LIMIT_MONTH:
                    return ConnectionsManager.getInstance(currentAccount).getCurrentTime() < until;
                case LIMIT_COUNT:
                default:
                    return true;
            }
        }
    }

    public final ArrayList<TLRPC.InputPeer> sendAs = new ArrayList<>();
    { sendAs.add(new TLRPC.TL_inputPeerSelf()); }
    private boolean loadingSendAs = false;
    private boolean loadedSendAs = false;

    public void loadSendAs() {
        if (loadingSendAs || loadedSendAs) {
            return;
        }
        loadingSendAs = true;
        ConnectionsManager.getInstance(currentAccount).sendRequest(new TLRPC.TL_stories_getChatsToSend(), (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            sendAs.clear();
            sendAs.add(new TLRPC.TL_inputPeerSelf());
            if (res instanceof TLRPC.TL_messages_chats) {
                ArrayList<TLRPC.Chat> chats = ((TLRPC.TL_messages_chats) res).chats;
                MessagesController.getInstance(currentAccount).putChats(chats, false);
                for (TLRPC.Chat chat : chats) {
                    TLRPC.InputPeer peer = MessagesController.getInputPeer(chat);
                    sendAs.add(peer);
                }
            }
            loadingSendAs = false;
            loadedSendAs = true;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesSendAsUpdate);
        }));
    }

    private void invalidateSendAsList() {
        // when channel gets deleted or something else happens...
        loadedSendAs = false;
    }

    public boolean canEditStories(long dialogId) {
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat == null) {
                return false;
            }
            return chat.creator || chat.admin_rights != null && chat.admin_rights.edit_stories;
        }
        return false;
    }

    public boolean canPostStories(long dialogId) {
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat)) {
                return false;
            }
            return chat.creator || chat.admin_rights != null && chat.admin_rights.post_stories;
        }
        return false;
    }

    public boolean canEditStory(TLRPC.StoryItem storyItem) {
        if (storyItem == null) {
            return false;
        }
        if (storyItem.dialogId == getSelfUserId()) {
            return false;
        }
        if (storyItem.dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-storyItem.dialogId);
            if (chat == null) {
                return false;
            }
            if (chat.creator) {
                return true;
            }
            if (storyItem.out && chat.admin_rights != null && (chat.admin_rights.post_stories || chat.admin_rights.edit_stories)) {
                return true;
            }
            if (!storyItem.out && chat.admin_rights != null && chat.admin_rights.edit_stories) {
                return true;
            }
        }
        return false;
    }

    public boolean canDeleteStory(TLRPC.StoryItem storyItem) {
        if (storyItem == null) {
            return false;
        }
        if (storyItem.dialogId == getSelfUserId()) {
            return false;
        }
        if (storyItem.dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-storyItem.dialogId);
            if (chat == null) {
                return false;
            }
            if (chat.creator) {
                return true;
            }
            if (storyItem.out && chat.admin_rights != null && (chat.admin_rights.post_stories || chat.admin_rights.delete_stories)) {
                return true;
            }
            if (!storyItem.out && chat.admin_rights != null && chat.admin_rights.delete_stories) {
                return true;
            }
        }
        return false;
    }
}
