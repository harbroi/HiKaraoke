package net.harbroi.hikaraoke;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.squareup.picasso.Picasso;

import java.util.List;

public class YouTubeVideoAdapter extends ArrayAdapter<YouTubeVideoAdapter.VideoItemData> {

    public interface OnAddToQueueClickListener {
        void onAddToQueueClick(VideoItemData item);
    }

    public static class VideoItemData {
        public final String firebaseKey;
        public final String videoId;
        public final String title;
        public final String thumbnailUrl;
        public final String duration;

        public VideoItemData(String videoId, String title, String thumbnailUrl, String duration) {
            this(null, videoId, title, thumbnailUrl, duration);
        }

        public VideoItemData(String firebaseKey, String videoId, String title, String thumbnailUrl, String duration) {
            this.firebaseKey = firebaseKey;
            this.videoId = videoId;
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.duration = duration;
        }
    }

    private final LayoutInflater inflater;
    private final OnAddToQueueClickListener onAddToQueueClickListener;

    public YouTubeVideoAdapter(Context context, List<VideoItemData> items) {
        this(context, items, null);
    }

    public YouTubeVideoAdapter(Context context, List<VideoItemData> items, OnAddToQueueClickListener onAddToQueueClickListener) {
        super(context, R.layout.list_item_video, items);
        this.inflater = LayoutInflater.from(context);
        this.onAddToQueueClickListener = onAddToQueueClickListener;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_video, parent, false);
            holder = new ViewHolder();
            holder.thumbnail = convertView.findViewById(R.id.videoThumbnail);
            holder.title = convertView.findViewById(R.id.videoTitle);
            holder.duration = convertView.findViewById(R.id.videoDuration);
            holder.addToQueueButton = convertView.findViewById(R.id.addToQueueButton);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        VideoItemData item = getItem(position);
        if (item != null) {
            holder.title.setText(item.title);
            holder.duration.setText(item.duration);

            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
                Picasso.get()
                        .load(item.thumbnailUrl)
                        .fit()
                        .centerCrop()
                        .into(holder.thumbnail);
            }

            if (onAddToQueueClickListener != null) {
                holder.addToQueueButton.setVisibility(View.VISIBLE);
                holder.addToQueueButton.setOnClickListener(v -> onAddToQueueClickListener.onAddToQueueClick(item));
            } else {
                holder.addToQueueButton.setVisibility(View.GONE);
                holder.addToQueueButton.setOnClickListener(null);
            }
        }

        return convertView;
    }

    private static class ViewHolder {
        ImageView thumbnail;
        TextView title;
        TextView duration;
        ImageView addToQueueButton;
    }
}

