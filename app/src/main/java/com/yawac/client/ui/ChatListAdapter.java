package com.yawac.client.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yawac.client.R;
import com.yawac.client.data.Models.Chat;
import com.yawac.client.data.Models.Message;

import java.util.ArrayList;
import java.util.List;

public class ChatListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onNewChat();
        void onChatClick(Chat chat);

        void onChatLongClick(Chat chat);
    }

    private final List<Chat> chats = new ArrayList<>();
    private final Listener listener;

    public ChatListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setChats(List<Chat> chats) {
        this.chats.clear();
        this.chats.addAll(chats);
        notifyDataSetChanged();
    }

    /** Adapter position of a stored chat, or -1. Used by interaction tests. */
    int positionOf(String chatId) {
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i).id.equals(chatId)) return i + 1;
        }
        return -1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType != 1) {
            View view = LayoutInflater.from(parent.getContext()).inflate(
                    viewType == 0 ? R.layout.item_chat_header : R.layout.item_chat_empty, parent, false);
            if (viewType == 0) NativeUi.newChatButton(view.findViewById(R.id.newChatButton));
            return new StaticHolder(view);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder row, int position) {
        if (position == 0) {
            row.itemView.findViewById(R.id.newChatButton).setOnClickListener(v -> listener.onNewChat());
            return;
        }
        if (!(row instanceof Holder)) return;
        Holder holder = (Holder) row;
        Chat chat = chats.get(position - 1);
        String title = chat.title == null || chat.title.isEmpty()
                ? Chat.DEFAULT_TITLE : chat.title;
        holder.title.setText(title);

        String preview = chat.preview();
        holder.preview.setText(preview.isEmpty() ? "暂无消息" : preview);

        Message last = chat.lastConversationMessage();
        holder.time.setText(last == null ? "" : TimeFormat.formatShort(last.timestamp));

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onChatClick(chat);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onChatLongClick(chat);
                return true;
            }
        });
        holder.surface.setContentDescription(title + "，" + holder.time.getText() + "，" + holder.preview.getText() + "，长按删除");
        holder.surface.setOnClickListener(v -> listener.onChatClick(chat));
        holder.surface.setOnLongClickListener(v -> { listener.onChatLongClick(chat); return true; });
    }

    @Override
    public int getItemCount() {
        return 1 + Math.max(1, chats.size());
    }

    @Override public int getItemViewType(int position) {
        return position == 0 ? 0 : chats.isEmpty() ? 2 : 1;
    }

    private static class StaticHolder extends RecyclerView.ViewHolder {
        StaticHolder(View view) { super(view); }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View surface;
        TextView title;
        TextView preview;
        TextView time;

        Holder(@NonNull View itemView) {
            super(itemView);
            surface = NativeUi.historyCard(itemView.findViewById(R.id.historyCard));
            title = itemView.findViewById(R.id.title);
            preview = itemView.findViewById(R.id.preview);
            time = itemView.findViewById(R.id.time);
        }
    }
}
