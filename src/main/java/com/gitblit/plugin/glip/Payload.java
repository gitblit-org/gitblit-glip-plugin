package com.gitblit.plugin.glip;


public class Payload {

	private String icon;
	private String activity;
	private String title;
	private String body;

	private transient String conversation;

	public Payload() {
	}

	public Payload icon(String icon) {
		setIcon(icon);
		return this;
	}

	public Payload activity(String activity) {
		setActivity(activity);
		return this;
	}

	public Payload title(String title) {
		setTitle(title);
		return this;
	}

	public Payload body(String message) {
		setBody(message);
		return this;
	}

	public Payload conversation(String conversation) {
		setConversation(conversation);
		return this;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public String getActivity() {
		return activity;
	}

	public void setActivity(String activity) {
		this.activity = activity;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body == null ? null : body.trim();
	}

	public String getConversation() {
		return conversation;
	}

	public void setConversation(String room) {
		this.conversation = room;
	}

	public String getEndPoint(String token) {
		return String.format("https://hooks.glip.com/webhook/%s", token);
	}
}
