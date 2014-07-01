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
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.fortsoft.pf4j.Extension;

import com.gitblit.Constants;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.extensions.TicketHook;
import com.gitblit.manager.IGitblit;
import com.gitblit.manager.IRepositoryManager;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.manager.IUserManager;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.TicketModel;
import com.gitblit.models.TicketModel.Change;
import com.gitblit.models.TicketModel.Patchset;
import com.gitblit.models.TicketModel.Review;
import com.gitblit.models.UserModel;
import com.gitblit.servlet.GitblitContext;
import com.gitblit.utils.ActivityUtils;
import com.gitblit.utils.StringUtils;

/**
 * This hook will post a message to a conversation when a ticket is created or updated.
 *
 * @author James Moger
 *
 */
@Extension
public class GlipTicketHook extends TicketHook {

	final String name = getClass().getSimpleName();

	final Logger log = LoggerFactory.getLogger(getClass());

	final Glip glip;

	final IStoredSettings settings;

	public GlipTicketHook() {
		super();

		IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
		Glip.init(runtimeManager);
    	glip = Glip.instance();
    	settings = runtimeManager.getSettings();
	}

    @Override
    public void onNewTicket(TicketModel ticket) {
    	if (!shallPost(ticket)) {
			return;
		}

		Set<TicketModel.Field> fieldExclusions = new HashSet<TicketModel.Field>();
		fieldExclusions.addAll(Arrays.asList(TicketModel.Field.watchers, TicketModel.Field.voters,
				TicketModel.Field.status, TicketModel.Field.mentions, TicketModel.Field.title));

    	Change change = ticket.changes.get(0);
    	IUserManager userManager = GitblitContext.getManager(IUserManager.class);

    	UserModel reporter = userManager.getUserModel(change.author);

    	String activity = String.format("%s has created a ticket for %s", reporter.getDisplayName(),
    			StringUtils.stripDotGit(ticket.repository));

    	StringBuilder sb = new StringBuilder();
    	sb.append(String.format("**%s** [ticket-%s](%s): %s\n", StringUtils.stripDotGit(ticket.repository),
    			ticket.number, getUrl(ticket), ticket.title));

    	fields(sb, ticket, ticket.changes.get(0), fieldExclusions);

    	Payload payload = new Payload()
    		.icon(getIconUrl(reporter))
			.activity(activity)
			.body(sb.toString());

   		glip.sendAsync(payload);
    }

    @Override
    public void onUpdateTicket(TicketModel ticket, Change change) {
    	if (!shallPost(ticket)) {
			return;
		}
		Set<TicketModel.Field> fieldExclusions = new HashSet<TicketModel.Field>();
		fieldExclusions.addAll(Arrays.asList(TicketModel.Field.watchers, TicketModel.Field.voters,
				TicketModel.Field.mentions, TicketModel.Field.title, TicketModel.Field.body,
				TicketModel.Field.mergeSha));

		IUserManager userManager = GitblitContext.getManager(IUserManager.class);
		UserModel user = userManager.getUserModel(change.author);
		String author = user.getDisplayName();
		String activity = null;
		String body = null;

		if (change.hasReview()) {
			/*
			 * Patchset review
			 */
    		activity = String.format("%s has reviewed %s patchset %s-%s", author,
    				StringUtils.stripDotGit(ticket.repository),
    				change.review.patchset, change.review.rev);

    		String emoji = "";
    		switch (change.review.score) {
    		case approved:
    			emoji = ":white_check_mark:";
    			break;
    		case looks_good:
    			emoji = ":thumbsup:";
    			break;
    		case needs_improvement:
    			emoji = ":thumbsdown:";
    			break;
    		case vetoed:
    			emoji = ":no_entry_sign:";
    			break;
    		default:
    			break;
    		}

			StringBuilder sb = new StringBuilder();

    		Review review = change.review;
    		String d = settings.getString(Keys.web.datestampShortFormat, "yyyy-MM-dd");
			String t = settings.getString(Keys.web.timeFormat, "HH:mm");
			DateFormat df = new SimpleDateFormat(d + " " + t);
			List<Change> reviews = ticket.getReviews(ticket.getPatchset(review.patchset, review.rev));
			sb.append("|**Date**|**Reviewer**|**Score**|**Description**|\n");
			for (Change c : reviews) {
				String name = c.author;
				UserModel u = userManager.getUserModel(change.author);
				if (u != null) {
					name = u.getDisplayName();
				}
				String score;
				if (change.review.score.getValue() > 0) {
					score = "+" + c.review.score.getValue();
				} else {
					score = "" + c.review.score.getValue();
				}
				String eval = String.format("%s (%s)", emoji, score);
				String date = df.format(c.date);
				sb.append(String.format("|%1$s|%2$s|%3$s|%4$s|\n",
						date, name, eval, c.review.score.toString()));
			}
			sb.append("\n");
			body = sb.toString();

		} else if (change.hasPatchset()) {
			/*
			 * New Patchset
			 */
			String tip = change.patchset.tip;
			String base;
			if (change.patchset.rev == 1) {
				if (change.patchset.number == 1) {
					/*
					 * Initial proposal
					 */
					activity = String.format("%s has pushed a proposal for %s", author,
							StringUtils.stripDotGit(ticket.repository));
				} else {
					/*
					 * Rewritten patchset
					 */
					activity = String.format("%s has rewritten a %s patchset (%s)", author,
							StringUtils.stripDotGit(ticket.repository), change.patchset.type);
				}
				base = change.patchset.base;
			} else {
				/*
				 * Fast-forward patchset update
				 */
				activity = String.format("%s has added %s %s to a %s ticket", author, change.patchset.added,
						change.patchset.added == 1 ? "commit" : "commits", StringUtils.stripDotGit(ticket.repository));
				Patchset prev = ticket.getPatchset(change.patchset.number, change.patchset.rev - 1);
				base = prev.tip;
			}

			// show the fields above the commit list
			StringBuilder sb = new StringBuilder();
			fields(sb, ticket, change, fieldExclusions);

			// abbreviated commit list
			List<RevCommit> commits = getCommits(ticket.repository, base, tip);
			sb.append("\n\n");
			int shortIdLen = settings.getInteger(Keys.web.shortCommitIdLength, 6);
			int maxCommits = 5;
			for (int i = 0; i < Math.min(maxCommits, commits.size()); i++) {
				RevCommit commit = commits.get(i);
				String username = "";
				String email = "";
				if (commit.getAuthorIdent().getEmailAddress() != null) {
					username = commit.getAuthorIdent().getName();
					email = commit.getAuthorIdent().getEmailAddress().toLowerCase();
					if (StringUtils.isEmpty(username)) {
						username = email;
					}
				} else {
					username = commit.getAuthorIdent().getName();
					email = username.toLowerCase();
				}
//				String gravatarUrl = ActivityUtils.getGravatarThumbnailUrl(email, 16);
				String commitUrl = getUrl(ticket.repository, null, commit.getName());
				String shortId = commit.getName().substring(0, shortIdLen);
				String shortMessage = StringUtils.trimString(commit.getShortMessage(), Constants.LEN_SHORTLOG);
//				String row = String.format("|![%s](%s)|[%s](%s)|%s|\n",
//						username, gravatarUrl, shortId, commitUrl, shortMessage);
				String row = String.format("|%s|[%s](%s)|%s|\n",
						username, shortId, commitUrl, shortMessage);
				sb.append(row);
			}
			sb.append("\n");

			// compare link
			if (commits.size() > 1) {
				String compareUrl = getUrl(ticket.repository, base, tip);
				String compareText;
				if (commits.size() > maxCommits) {
					int diff = commits.size() - maxCommits;
					if (diff == 1) {
						compareText = "1 more commit";
					} else {
						compareText = String.format("%d more commits", diff);
					}
				} else {
					compareText = String.format("view comparison of these %s commits", commits.size());
				}
				sb.append(String.format("[%s](%s)\n", compareText, compareUrl));
			}

			body = sb.toString();

		} else if (change.isMerge()) {
			/*
			 * Merged
			 */
			activity = String.format("%s has merged a %s ticket", author, StringUtils.stripDotGit(ticket.repository));
		} else if (change.isStatusChange()) {
			/*
			 * Status Change
			 */
			activity = String.format("%s has changed the status of a %s ticket", author,
					StringUtils.stripDotGit(ticket.repository));
		} else if (change.hasComment() && settings.getBoolean(Plugin.SETTING_POST_TICKET_COMMENTS, true)) {
			/*
			 * Comment
			 */
			activity = String.format("%s has commented on a %s ticket", author,
					StringUtils.stripDotGit(ticket.repository));
		}

		if (activity == null) {
			// not a change we are reporting
			return;
		}

		StringBuilder sb = new StringBuilder();
		sb.append(String.format("**%s** [ticket-%s](%s): %s\n", StringUtils.stripDotGit(ticket.repository),
				ticket.number, getUrl(ticket), ticket.title));
		if (!StringUtils.isEmpty(body)) {
			sb.append(body.trim());
		}

		// fields on patchset changes are output above this point
		if (!change.hasPatchset()) {
			fields(sb, ticket, change, fieldExclusions);
		}

    	Payload payload = new Payload()
    		.icon(getIconUrl(user))
    		.activity(activity)
    		.body(sb.toString());

		IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
		RepositoryModel repository = repositoryManager.getRepositoryModel(ticket.repository);
   		glip.setConversation(repository, payload);
   		glip.sendAsync(payload);
    }

	/**
	 * Returns the icon url for the event.  This may be an icon url from settings or the gravatar
	 * of the user.
	 *
	 * @param user
	 * @return an url
	 */
    protected String getIconUrl(UserModel user) {
		String iconUrl = settings.getString(Plugin.SETTING_TICKET_ICON, null);
		if (StringUtils.isEmpty(iconUrl) && !StringUtils.isEmpty(user.emailAddress)) {
			iconUrl = ActivityUtils.getGravatarThumbnailUrl(user.emailAddress, 48);
		}
		return iconUrl;
    }

    protected void fields(StringBuilder sb, TicketModel ticket, Change change, Set<TicketModel.Field> fieldExclusions) {
    	Map<TicketModel.Field, String> filtered = new HashMap<TicketModel.Field, String>();
    	if (change.hasFieldChanges()) {
    		for (Map.Entry<TicketModel.Field, String> fc : change.fields.entrySet()) {
    			if (!fieldExclusions.contains(fc.getKey())) {
    				// field is included
    				filtered.put(fc.getKey(), fc.getValue());
    			}
    		}
    	}

    	if (change.hasComment() && settings.getBoolean(Plugin.SETTING_POST_TICKET_COMMENTS, true)) {
    		sb.append("\n");
    		String comment = change.comment.text;
    		sb.append(comment);
    	}

    	// sort by field ordinal
    	List<TicketModel.Field> fields = new ArrayList<TicketModel.Field>(filtered.keySet());
    	Collections.sort(fields);

    	if (fields.size() > 0) {
			sb.append("\n");
			for (TicketModel.Field field : fields) {
				String value;
				if (filtered.get(field) == null) {
					continue;
				} else {
					value = filtered.get(field);

    				if (TicketModel.Field.responsible == field) {
    					// lookup display name of the user
    					value = getDisplayName(value);
    				}
				}
				sb.append(String.format("|**%1$s**|%2$s|\n", field.name(), value));
			}
			sb.append("\n");
    	}
    }

    protected String getDisplayName(String username) {
    	if (StringUtils.isEmpty(username)) {
    		return username;
    	}

		IUserManager userManager = GitblitContext.getManager(IUserManager.class);
		UserModel user = userManager.getUserModel(username);
		if (user != null) {
			String displayName = user.getDisplayName();
			if (!StringUtils.isEmpty(displayName) && !username.equals(displayName)) {
				return displayName;
			}
		}
		return username;
    }

    /**
     * Determine if a ticket should be posted to a Glip conversation.
     *
     * @param ticket
     * @return true if the ticket should be posted to a Glip conversation
     */
    protected boolean shallPost(TicketModel ticket) {
    	IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
    	boolean shallPostTicket = runtimeManager.getSettings().getBoolean(Plugin.SETTING_POST_TICKETS, true);
    	if (!shallPostTicket) {
    		return false;
    	}

		IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
		RepositoryModel repository = repositoryManager.getRepositoryModel(ticket.repository);
		boolean shallPostRepo = glip.shallPost(repository);
		return shallPostRepo;
    }

    protected String getUrl(TicketModel ticket) {
    	return GitblitContext.getManager(IGitblit.class).getTicketService().getTicketUrl(ticket);
    }

    /**
     * Returns a link appropriate for the push.
     *
     * If both new and old ids are null, the summary page link is returned.
     *
     * @param repo
     * @param oldId
     * @param newId
     * @return a link
     */
    protected String getUrl(String repo, String oldId, String newId) {
    	IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
		String canonicalUrl = runtimeManager.getSettings().getString(Keys.web.canonicalUrl, "https://localhost:8443");

		if (oldId == null && newId != null) {
			// create
			final String hrefPattern = "{0}/commit?r={1}&h={2}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo, newId);
		} else if (oldId != null && newId == null) {
			// log
			final String hrefPattern = "{0}/log?r={1}&h={2}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo, oldId);
		} else if (oldId != null && newId != null) {
			// update/compare
			final String hrefPattern = "{0}/compare?r={1}&h={2}..{3}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo, oldId, newId);
		} else if (oldId == null && newId == null) {
			// summary page
			final String hrefPattern = "{0}/summary?r={1}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo);
		}

		return null;
    }

    private List<RevCommit> getCommits(String repositoryName, String baseId, String tipId) {
    	IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
    	Repository db = repositoryManager.getRepository(repositoryName);
    	List<RevCommit> list = new ArrayList<RevCommit>();
		RevWalk walk = new RevWalk(db);
		walk.reset();
		walk.sort(RevSort.TOPO);
		walk.sort(RevSort.REVERSE, true);
		try {
			RevCommit tip = walk.parseCommit(db.resolve(tipId));
			RevCommit base = walk.parseCommit(db.resolve(baseId));
			walk.markStart(tip);
			walk.markUninteresting(base);
			for (;;) {
				RevCommit c = walk.next();
				if (c == null) {
					break;
				}
				list.add(c);
			}
		} catch (IOException e) {
			// Should never happen, the core receive process would have
			// identified the missing object earlier before we got control.
			log.error("failed to get commits", e);
		} finally {
			walk.release();
			db.close();
		}
		return list;
	}
}