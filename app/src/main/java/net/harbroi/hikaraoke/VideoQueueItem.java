package net.harbroi.hikaraoke;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class VideoQueueItem {
    public String videoId;
    public String title;
    public String description;
    public long duration;
    public String thumbnailUrl;

    public VideoQueueItem() {
        // Required for Firebase
    }

    public VideoQueueItem(String videoId, String title, String description, long duration, String thumbnailUrl) {
        this.videoId = videoId;
        this.title = title;
        this.description = description;
        this.duration = duration;
        this.thumbnailUrl = thumbnailUrl;
    }
}
