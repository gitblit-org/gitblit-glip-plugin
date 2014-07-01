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

import ro.fortsoft.pf4j.PluginWrapper;
import ro.fortsoft.pf4j.Version;

import com.gitblit.extensions.GitblitPlugin;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.servlet.GitblitContext;

public class Plugin extends GitblitPlugin {

	public static final String SETTING_DEFAULT_TOKEN = "glip.defaultToken";

	public static final String SETTING_CONVERSATION_TOKEN = "glip.%s.token";

	public static final String SETTING_USE_PROJECT_CONVERSATIONS = "glip.useProjectConversation";

	public static final String SETTING_DEFAULT_ICON = "glip.defaultIcon";

	public static final String SETTING_TICKET_ICON = "glip.ticketIcon";

	public static final String SETTING_GIT_ICON = "glip.gitIcon";

	public static final String SETTING_POST_PERSONAL_REPOS = "glip.postPersonalRepos";

	public static final String SETTING_POST_TICKETS = "glip.postTickets";

	public static final String SETTING_POST_TICKET_COMMENTS = "glip.postTicketComments";

	public static final String SETTING_POST_BRANCHES = "glip.postBranches";

	public static final String SETTING_POST_TAGS = "glip.postTags";

	public Plugin(PluginWrapper wrapper) {
		super(wrapper);

		IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
		Glip.init(runtimeManager);
	}

	@Override
	public void start() {
		log.debug("{} STARTED.", getWrapper().getPluginId());
	}

	@Override
	public void stop() {
		Glip.instance().stop();
		log.debug("{} STOPPED.", getWrapper().getPluginId());
	}

	@Override
	public void onInstall() {
		log.debug("{} INSTALLED.", getWrapper().getPluginId());
	}

	@Override
	public void onUpgrade(Version oldVersion) {
		log.debug("{} UPGRADED from {}.", getWrapper().getPluginId(), oldVersion);
	}

	@Override
	public void onUninstall() {
		log.debug("{} UNINSTALLED.", getWrapper().getPluginId());
	}
}
