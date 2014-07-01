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

import java.io.IOException;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import ro.fortsoft.pf4j.Extension;

import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.models.UserModel;
import com.gitblit.servlet.GitblitContext;
import com.gitblit.transport.ssh.commands.CommandMetaData;
import com.gitblit.transport.ssh.commands.DispatchCommand;
import com.gitblit.transport.ssh.commands.SshCommand;
import com.gitblit.transport.ssh.commands.UsageExample;
import com.gitblit.transport.ssh.commands.UsageExamples;
import com.gitblit.utils.ActivityUtils;
import com.gitblit.utils.StringUtils;

@Extension
@CommandMetaData(name = "glip", description = "Glip commands")
public class GlipDispatcher extends DispatchCommand {

	@Override
	protected void setup() {
		boolean canAdmin = getContext().getClient().getUser().canAdmin();
		if (canAdmin) {
			register(TestCommand.class);
			register(MessageCommand.class);
		}
	}

	@CommandMetaData(name = "test", description = "Post a test message")
	@UsageExamples(examples = {
			@UsageExample(syntax = "${cmd}", description = "Posts a test message to the default conversation"),
			@UsageExample(syntax = "${cmd} aConversation", description = "Posts a test message to aConversation")
	})
	public static class TestCommand extends SshCommand {

		@Argument(index = 0, metaVar = "CONVERSATION", usage = "Destination conversation for message")
		String conversation;

		/**
		 * Post a test message
		 */
		@Override
		public void run() throws Failure {
			UserModel user = getContext().getClient().getUser();
			String iconUrl = null;
			if (!StringUtils.isEmpty(user.emailAddress)) {
				iconUrl = ActivityUtils.getGravatarThumbnailUrl(user.emailAddress, 48);
			}

	    	IStoredSettings settings = getContext().getGitblit().getSettings();
			String canonicalUrl = settings.getString(Keys.web.canonicalUrl, "https://localhost:8443");

		    Payload payload = new Payload();
		    payload.icon(iconUrl);
		    payload.activity(String.format("%s sent a message", user.getDisplayName()));
		    payload.title("Test message from Gitblit");
		    payload.body(String.format("This is a **test** message sent from your [Gitblit](%s).", canonicalUrl));

		    if (!StringUtils.isEmpty(conversation)) {
		    	payload.setConversation(conversation);
		    }

			try {
				IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
				Glip.init(runtimeManager);
				Glip.instance().send(payload);
			} catch (IOException e) {
			    throw new Failure(1, e.getMessage(), e);
			}
		}
	}

	@CommandMetaData(name = "send", aliases = { "post" }, description = "Asynchronously post a message")
	@UsageExamples(examples = {
			@UsageExample(syntax = "${cmd} -t\"'this is my title'\" -m \"'this is a test'\"", description = "Asynchronously posts a message to the default conversation"),
			@UsageExample(syntax = "${cmd} aConversation -t\"'this is my title'\" -m \"'this is a test'\"", description = "Asynchronously posts a message to aConversation")
	})
	public static class MessageCommand extends SshCommand {

		@Argument(index = 0, metaVar = "CONVERSATION", usage = "Destination Conversation for message")
		String conversation;

		@Option(name = "--activity", aliases = {"-a" }, metaVar = "ACTIVITY", required = false)
		String activity;

		@Option(name = "--title", aliases = {"-t" }, metaVar = "TITLE", required = false)
		String title;

		@Option(name = "--message", aliases = {"-m" }, metaVar = "MESSAGE", required = true)
		String message;

		/**
		 * Post a message
		 */
		@Override
		public void run() throws Failure {
			UserModel user = getContext().getClient().getUser();
			String iconUrl = null;
			if (!StringUtils.isEmpty(user.emailAddress)) {
				iconUrl = ActivityUtils.getGravatarThumbnailUrl(user.emailAddress, 48);
			}

			if (activity == null) {
				activity = String.format("%s sent a message", user.getDisplayName());
			}

		    Payload payload = new Payload();
		    payload.icon(iconUrl);
		    payload.activity(activity);
		    payload.title(title);
		    payload.body(message);

		    if (!StringUtils.isEmpty(conversation)) {
		    	payload.setConversation(conversation);
		    }

			IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
			Glip.init(runtimeManager);
		    Glip.instance().sendAsync(payload);
		}
	}
}

