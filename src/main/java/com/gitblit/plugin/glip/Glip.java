/*
 * Copyright 2014 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.plugin.glip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.AllClientPNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants;
import com.gitblit.manager.IManager;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.models.RepositoryModel;
import com.gitblit.utils.JsonUtils.GmtDateTypeAdapter;
import com.gitblit.utils.StringUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Configures the final payload and sends a Glip message.
 *
 * @author James Moger
 *
 */
public class Glip implements IManager {

	private static Glip instance;

	final Logger log = LoggerFactory.getLogger(getClass());

	final IRuntimeManager runtimeManager;

	final ExecutorService taskPool;

	public static void init(IRuntimeManager manager) {
		if (instance == null) {
			instance = new Glip(manager);
		}
	}

	public static Glip instance() {
		return instance;
	}

	Glip(IRuntimeManager runtimeManager) {
		this.runtimeManager = runtimeManager;
		this.taskPool = Executors.newCachedThreadPool();
	}

	@Override
	public Glip start() {
		return this;
	}

	@Override
	public Glip stop() {
		this.taskPool.shutdown();
		return this;
	}

	/**
	 * Returns true if the repository can be posted to Glip.
	 *
	 * @param repository
	 * @return true if the repository can be posted to Glip
	 */
	public boolean shallPost(RepositoryModel repository) {
		boolean postPersonalRepos = runtimeManager.getSettings().getBoolean(Plugin.SETTING_POST_PERSONAL_REPOS, false);
		if (repository.isPersonalRepository() && !postPersonalRepos) {
			return false;
		}
		return true;
	}

	/**
	 * Optionally sets the conversation of the payload based on the repository.
	 *
	 * @param repository
	 * @param payload
	 */
	public void setConversation(RepositoryModel repository, Payload payload) {
		boolean useProjectConversation = runtimeManager.getSettings().getBoolean(Plugin.SETTING_USE_PROJECT_CONVERSATIONS, false);
		if (!useProjectConversation) {
			return;
		}

		if (StringUtils.isEmpty(repository.projectPath)) {
			return;
		}

		payload.setConversation(repository.projectPath);
	}

	/**
	 * Asynchronously send a payload message.
	 *
	 * @param payload
	 * @throws IOException
	 */
	public void sendAsync(final Payload payload) {
		taskPool.submit(new GlipTask(this, payload));
	}

	/**
	 * Send a payload message.
	 *
	 * @param payload
	 * @throws IOException
	 */
	public void send(Payload payload) throws IOException {

		String conversation = payload.getConversation();
		String token;

		if (StringUtils.isEmpty(conversation)) {
			// default conversation
			token = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_TOKEN, null);
		} else {
			// specified conversation, validate token
			token = runtimeManager.getSettings().getString(String.format(Plugin.SETTING_CONVERSATION_TOKEN, conversation), null);
			if (StringUtils.isEmpty(token)) {
				token = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_TOKEN, null);
				log.warn("No Glip API token specified for '{}', defaulting to default conversation'", payload.getConversation());
				log.warn("Please set '{} = TOKEN' in gitblit.properties", String.format(Plugin.SETTING_CONVERSATION_TOKEN, conversation));
			}
		}

		Gson gson = new GsonBuilder().registerTypeAdapter(Date.class, new GmtDateTypeAdapter()).create();
		String json = gson.toJson(payload);
		log.debug(json);

		HttpClient client = new DefaultHttpClient();
		client.getParams().setParameter(AllClientPNames.CONNECTION_TIMEOUT, 5000);
		client.getParams().setParameter(AllClientPNames.SO_TIMEOUT, 5000);

		String conversationUrl = payload.getEndPoint(token);
		HttpPost post = new HttpPost(conversationUrl);
		post.getParams().setParameter(CoreProtocolPNames.USER_AGENT, Constants.NAME + "/" + Constants.getVersion());
		post.getParams().setParameter(CoreProtocolPNames.HTTP_CONTENT_CHARSET, "UTF-8");

		// post as JSON
		StringEntity entity = new StringEntity(json, "UTF-8");
		entity.setContentType("application/json");
		post.setEntity(entity);

		HttpResponse response = client.execute(post);
		int rc = response.getStatusLine().getStatusCode();

		if (HttpStatus.SC_OK == rc) {
			// This is the expected result code
			// replace this with post.closeConnection() after JGit updates to HttpClient 4.2
			post.abort();
		} else {
			String result = null;
			InputStream is = response.getEntity().getContent();
			try {
				byte [] buffer = new byte[8192];
				ByteArrayOutputStream os = new ByteArrayOutputStream();
				int len = 0;
				while ((len = is.read(buffer)) > -1) {
					os.write(buffer, 0, len);
				}
				result = os.toString("UTF-8");
			} finally {
				if (is != null) {
					is.close();
				}
			}

			log.error("Glip plugin sent:");
			log.error(json);
			log.error("Glip returned:");
			log.error(result);

			throw new IOException(String.format("Glip Error (%s): %s", rc, result));
		}
	}

	private static class GlipTask implements Serializable, Callable<Boolean> {

		private static final long serialVersionUID = 1L;

		final Logger log = LoggerFactory.getLogger(getClass());
		final Glip glip;
		final Payload payload;

		public GlipTask(Glip glip, Payload payload) {
			this.glip = glip;
			this.payload = payload;
		}

		@Override
		public Boolean call() {
			try {
				glip.send(payload);
				return true;
			} catch (IOException e) {
				log.error("Failed to send asynchronously to Glip!", e);
			}
			return false;
		}
	}
}
