package net.harbroi.hikaraoketv;

import androidx.annotation.NonNull;

import com.google.firebase.database.IgnoreExtraProperties;

@SuppressWarnings("unused")
@IgnoreExtraProperties
public class VideoItem {
    private String videoId;
    private String title;
    private String artist;
    private String description;
    private long duration;
    private String thumbnailUrl;

    public VideoItem() {
    }

    public VideoItem(String videoId, String title, String artist, String description, long duration) {
        this.videoId = videoId;
        this.title = title;
        this.artist = artist;
        this.description = description;
        this.duration = duration;
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    @Override
    public @NonNull String toString() {
        return "VideoItem{" +
                "videoId='" + videoId + '\'' +
                ", title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", duration=" + duration +
                ", thumbnailUrl='" + thumbnailUrl + '\'' +
                '}';
    }
}
