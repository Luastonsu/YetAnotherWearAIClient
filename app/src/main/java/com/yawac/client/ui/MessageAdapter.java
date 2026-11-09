package com.yawac.client.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;

import com.yawac.client.R;
import com.yawac.client.data.Models.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

import io.noties.markwon.ext.tables.TableAwareMovementMethod;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onRetry(Message message);
        void onRead(Message message);
    }

    private final Listener listener;

    public MessageAdapter() { this(null); }
    public MessageAdapter(Listener listener) { this.listener = listener; }

    private static final int TYPE_AI = 0;
    private static final int TYPE_USER = 1;
    private static final int TYPE_NOTICE = 2;
    private static final Object STATUS_UPDATE = new Object();

    private final List<Message> messages = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> pending = new HashSet<>(), entrance = new HashSet<>();
    private boolean initialized;
    private Context displayContext;
    private String appearanceKey;

    private String currentAppearance() {
        return ChatAppearance.size(displayContext) + ":" + ChatAppearance.color(displayContext, true)
                + ":" + ChatAppearance.color(displayContext, false) + ":"
                + Float.floatToIntBits(displayContext.getResources().getDisplayMetrics().scaledDensity);
    }

    public void setMessages(List<Message> list) {
        String appearance = displayContext == null ? null : currentAppearance();
        boolean appearanceChanged = appearanceKey != null && !appearanceKey.equals(appearance);
        appearanceKey = appearance;
        List<Row> before = new ArrayList<>(rows), after = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) after.add(new Row(list.get(i), i == list.size() - 1));
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return after.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                Row a = before.get(oldPosition), b = after.get(newPosition);
                return Objects.equals(a.id, b.id) && Objects.equals(a.role, b.role);
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                Row a = before.get(oldPosition), b = after.get(newPosition);
                return !appearanceChanged && a.sameBody(b) && Objects.equals(a.stage, b.stage) && a.started == b.started;
            }
            @Override public Object getChangePayload(int oldPosition, int newPosition) {
                return !appearanceChanged && before.get(oldPosition).sameBody(after.get(newPosition)) ? STATUS_UPDATE : null;
            }
        }, false);
        Set<String> oldIds = new HashSet<>(), newIds = new HashSet<>(), nextPending = new HashSet<>();
        for (Message m : messages) oldIds.add(m.id);
        for (Message m : list) {
            newIds.add(m.id);
            if (m.streaming) nextPending.add(m.id);
            else if (!Message.ROLE_NOTICE.equals(m.role) && initialized
                    && (!oldIds.contains(m.id) || pending.contains(m.id))) entrance.add(m.id);
        }
        entrance.retainAll(newIds);
        pending.clear(); pending.addAll(nextPending); initialized = true;
        messages.clear();
        messages.addAll(list);
        rows.clear(); rows.addAll(after);
        changes.dispatchUpdatesTo(this);
    }

    /** Repository Messages mutate in place, so comparisons must retain prior field values. */
    private static final class Row {
        final Message message;
        final String id, role, content, stage;
        final boolean failed, streaming, last;
        final long timestamp, started;
        Row(Message m, boolean last) {
            message = m; id = m.id; role = m.role; content = m.content; stage = m.stage;
            failed = m.failed; streaming = m.streaming; timestamp = m.timestamp;
            started = m.requestStartedAt; this.last = last;
        }
        boolean sameBody(Row b) {
            return message == b.message && Objects.equals(content, b.content) && failed == b.failed
                    && streaming == b.streaming && last == b.last && timestamp == b.timestamp;
        }
    }

    public void addMessage(Message message) {
        List<Message> next = new ArrayList<>(messages); next.add(message); setMessages(next);
    }

    public void removeMessage(Message message) {
        int index = messages.indexOf(message);
        if (index >= 0) {
            List<Message> next = new ArrayList<>(messages); next.remove(index); setMessages(next);
        }
    }

    public void notifyMessageChanged(Message message) {
        int index = messages.indexOf(message);
        if (index >= 0) {
            setMessages(new ArrayList<>(messages));
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (displayContext == null) {
            displayContext = parent.getContext().getApplicationContext();
            appearanceKey = currentAppearance();
        }
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_NOTICE) {
            return new NoticeHolder(inflater.inflate(R.layout.item_message_notice, parent, false));
        }
        if (viewType == TYPE_AI) {
            return new AiHolder(inflater.inflate(R.layout.item_message_ai, parent, false));
        }
        return new UserHolder(inflater.inflate(R.layout.item_message_user, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message m = messages.get(position);
        if (holder instanceof NoticeHolder) {
            ((NoticeHolder) holder).content.setText(m.content == null ? "" : m.content);
            return;
        }
        if (holder instanceof AiHolder) {
            AiHolder h = (AiHolder) holder;
            ChatAppearance.apply(h.content, false);
            h.content.setMovementMethod(TableAwareMovementMethod.create());
            String renderText = m.content == null ? "" : m.content;
            if (m.failed) {
                h.content.setMinWidth(0);
                h.content.setText(renderText);
            } else {
                Markdown.render(h.content, renderText);
            }
            bindStatus(h, m);
            h.content.setVisibility(m.streaming && renderText.isEmpty() ? View.GONE : View.VISIBLE);
            h.errorHint.setVisibility(m.failed ? View.VISIBLE : View.GONE);
            h.errorHint.setText(m.failed ? ErrorAdvice.forError(renderText) : "");
            boolean retry = m.failed && position == messages.size() - 1 && listener != null;
            boolean read = !m.failed && !m.streaming && !renderText.isEmpty() && listener != null;
            boolean showRead = read && (renderText.length() > 120 || renderText.contains("|") || renderText.contains("$$"));
            h.action.setVisibility(retry || showRead ? View.VISIBLE : View.GONE);
            h.action.setText(retry ? R.string.retry : R.string.read_reply);
            h.action.setOnClickListener(retry ? v -> listener.onRetry(m) : read ? v -> listener.onRead(m) : null);
            h.content.setOnLongClickListener(read ? v -> { listener.onRead(m); return true; } : null);
            h.time.setText(TimeFormat.format(m.timestamp));
            animateBody(h.content, m.id);
        } else {
            UserHolder h = (UserHolder) holder;
            ChatAppearance.apply(h.content, true);
            h.content.setText(m.content == null ? "" : m.content);
            h.time.setText(TimeFormat.format(m.timestamp));
            animateBody(h.content, m.id);
        }
    }

    private void animateBody(TextView view, String id) {
        ChatMotion.reset(view);
        if (entrance.remove(id)) ChatMotion.enter(view);
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof AiHolder) {
            ChatMotion.reset(((AiHolder) holder).content);
            Markdown.release(((AiHolder) holder).content);
        }
        else if (holder instanceof UserHolder) ChatMotion.reset(((UserHolder) holder).content);
        super.onViewRecycled(holder);
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                         @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof AiHolder) bindStatus((AiHolder) holder, messages.get(position));
        else onBindViewHolder(holder, position);
    }

    public void refreshWaitingTime() {
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).streaming) notifyItemChanged(i, STATUS_UPDATE);
    }

    public void notifyProgressChanged(String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(messageId)) {
                // Keep the holder and viewport; progress never changes the message body or list.
                notifyItemChanged(i, STATUS_UPDATE);
                return;
            }
        }
    }

    private void bindStatus(AiHolder holder, Message message) {
        holder.status.setVisibility(message.streaming ? View.VISIBLE : View.GONE);
        if (message.streaming) {
            long seconds = Math.max(0, (System.currentTimeMillis() - message.requestStartedAt) / 1000);
            String stage = message.stage.isEmpty() ? "生成回答中" : message.stage;
            holder.status.setText(stage + " · " + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60));
        }
    }

    @Override
    public int getItemViewType(int position) {
        Message message = messages.get(position);
        if (Message.ROLE_NOTICE.equals(message.role)) return TYPE_NOTICE;
        return "user".equals(message.role) ? TYPE_USER : TYPE_AI;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class AiHolder extends RecyclerView.ViewHolder {
        TextView status;
        TextView content;
        TextView time;
        TextView action;
        TextView errorHint;

        AiHolder(@NonNull View itemView) {
            super(itemView);
            status = itemView.findViewById(R.id.status);
            content = itemView.findViewById(R.id.content);
            time = itemView.findViewById(R.id.time);
            action = itemView.findViewById(R.id.messageAction);
            errorHint = itemView.findViewById(R.id.errorHint);
        }
    }

    static class UserHolder extends RecyclerView.ViewHolder {
        TextView content;
        TextView time;

        UserHolder(@NonNull View itemView) {
            super(itemView);
            content = itemView.findViewById(R.id.content);
            time = itemView.findViewById(R.id.time);
        }
    }

    static class NoticeHolder extends RecyclerView.ViewHolder {
        final TextView content;

        NoticeHolder(@NonNull View itemView) {
            super(itemView);
            content = itemView.findViewById(R.id.noticeText);
        }
    }
}
