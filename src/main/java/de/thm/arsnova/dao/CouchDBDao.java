/*
 * Copyright (C) 2012 THM webMedia
 * 
 * This file is part of ARSnova.
 *
 * ARSnova is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ARSnova is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.thm.arsnova.dao;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.fourspaces.couchdb.Database;
import com.fourspaces.couchdb.Document;
import com.fourspaces.couchdb.View;
import com.fourspaces.couchdb.ViewResults;

import de.thm.arsnova.entities.Feedback;
import de.thm.arsnova.entities.Session;
import de.thm.arsnova.entities.User;
import de.thm.arsnova.services.ISessionService;
import de.thm.arsnova.services.IUserService;
import de.thm.arsnova.socket.message.Question;

@Component
public class CouchDBDao implements IDatabaseDao {
	@Autowired
	IUserService userService;
	
	@Autowired
	ISessionService sessionService;
	
	private String databaseHost;
	private int databasePort;
	private String databaseName;
	
	private Database database;
	
	//
	
	public static final Logger logger = LoggerFactory.getLogger(CouchDBDao.class);

	@Value("${couchdb.host}")
	public final void setDatabaseHost(String databaseHost) {
		logger.info(databaseHost);
		this.databaseHost = databaseHost;
	}
	
	@Value("${couchdb.port}")
	public final void setDatabasePort(String databasePort) {
		logger.info(databasePort);
		this.databasePort = Integer.parseInt(databasePort);
	}
	
	@Value("${couchdb.name}")
	public final void setDatabaseName(String databaseName) {
		logger.info(databaseName);
		this.databaseName = databaseName;
	}
	
	/**
	 * This method cleans up old feedback votes at the scheduled interval.
	 */
	@Override
	public void cleanFeedbackVotes(int cleanupFeedbackDelay) {
		final long timelimitInMillis = 60000 * cleanupFeedbackDelay;
		final long maxAllowedTimeInMillis = System.currentTimeMillis() - timelimitInMillis;
		
		Map<String, Set<String>> affectedUsers = new HashMap<String, Set<String>>();
		Set<String> allAffectedSessions = new HashSet<String>();
		
		List<Document> results = findFeedbackForDeletion(maxAllowedTimeInMillis);
		for (Document d : results) {
			try {
				// Read the required document data
				Document feedback = this.getDatabase().getDocument(d.getId());
				String arsInternalSessionId = feedback.getString("sessionId");
				String user = feedback.getString("user");
				
				// Store user and session data for later. We need this to communicate the changes back to the users.
				Set<String> affectedArsSessions = affectedUsers.get(user);
				if (affectedArsSessions == null) {
					affectedArsSessions = new HashSet<String>();
				}
				affectedArsSessions.add(getSessionKeyword(arsInternalSessionId));
				affectedUsers.put(user, affectedArsSessions);
				allAffectedSessions.addAll(affectedArsSessions);
				
				this.database.deleteDocument(feedback);
				logger.debug("Cleaning up Feedback document " + d.getId());
			} catch (IOException e) {
				logger.error("Could not delete Feedback document " + d.getId());
			} catch (JSONException e) {
				logger.error("Could not delete Feedback document {}, error is: {} ", new Object[] {d.getId(), e});
			}
		}
		if (!results.isEmpty()) {
			sessionService.broadcastFeedbackChanges(affectedUsers, allAffectedSessions);
		}
	}
	


	private List<Document> findFeedbackForDeletion(final long maxAllowedTimeInMillis) {
		View cleanupFeedbackView = new View("understanding/cleanup");
		cleanupFeedbackView.setStartKey("null");
		cleanupFeedbackView.setEndKey(String.valueOf(maxAllowedTimeInMillis));
		ViewResults feedbackForCleanup = this.getDatabase().view(cleanupFeedbackView);
		return feedbackForCleanup.getResults();
	}
	
	

	@Override
	public Session getSession(String keyword) {
		Session result = this.getSessionFromKeyword(keyword);
		if (result.isActive() || result.getCreator().equals(this.actualUserName())) {
			sessionService.addUserToSessionMap(this.actualUserName(), keyword);
			return result;
		}
		
		return null;
	}
	
	@Override
	public Session getSessionFromKeyword(String keyword) {
		try {
			View view = new View("session/by_keyword");
			view.setKey(URLEncoder.encode("\"" + keyword + "\"", "UTF-8"));
			ViewResults results = this.getDatabase().view(view);
	
			if (results.getJSONArray("rows").optJSONObject(0) == null)
				return null;
	
			return (Session) JSONObject.toBean(
					results.getJSONArray("rows").optJSONObject(0).optJSONObject("value"), Session.class);
		} catch (UnsupportedEncodingException e) {
			return null;
		}
	}
	
	@Override
	public Session saveSession(Session session) {
		
		Document sessionDocument = new Document();
		sessionDocument.put("type","session");
		sessionDocument.put("name", session.getName());
		sessionDocument.put("shortName", session.getShortName());
		sessionDocument.put("keyword", sessionService.generateKeyword());
		sessionDocument.put("creator", this.actualUserName());
		sessionDocument.put("active", true);
		try {
			database.saveDocument(sessionDocument);
		} catch (IOException e) {
			return null;
		}
		
		return this.getSession(sessionDocument.getString("keyword"));
	}

	@Override
	public Feedback getFeedback(String keyword) {
		String sessionId = this.getSessionId(keyword);
		if (sessionId == null)
			return null;

		logger.info("Time: {}", this.currentTimestamp());

		View view = new View("understanding/by_session");
		view.setGroup(true);
		view.setStartKey(URLEncoder.encode("[\"" + sessionId + "\"]"));
		view.setEndKey(URLEncoder.encode("[\"" + sessionId + "\",{}]"));
		ViewResults results = this.getDatabase().view(view);

		logger.info("Feedback: {}", results.getJSONArray("rows"));

		int values[] = { 0, 0, 0, 0 };
		List<Integer> result = new ArrayList<Integer>();
		
		try {
			for (int i = 0; i <= 3; i++) {
				String key = results.getJSONArray("rows").optJSONObject(i)
						.optJSONArray("key").getString(1);
				if (key.equals("Bitte schneller"))
					values[0] = results.getJSONArray("rows").optJSONObject(i)
							.getInt("value");
				if (key.equals("Kann folgen"))
					values[1] = results.getJSONArray("rows").optJSONObject(i)
							.getInt("value");
				if (key.equals("Zu schnell"))
					values[2] = results.getJSONArray("rows").optJSONObject(i)
							.getInt("value");
				if (key.equals("Nicht mehr dabei"))
					values[3] = results.getJSONArray("rows").optJSONObject(i)
							.getInt("value");
			}
		} catch (Exception e) {
			return new Feedback(
					values[0],
					values[1],
					values[2],
					values[3]
			);
		}

		return new Feedback(
				values[0],
				values[1],
				values[2],
				values[3]
		);
	}

	@Override
	public boolean saveFeedback(String keyword, int value, de.thm.arsnova.entities.User user) {
		String sessionId = this.getSessionId(keyword);
		if (sessionId == null) return false;
		if (!(value >= 0 && value <= 3)) return false;
		
		Document feedback = new Document();
		List<Document> postedFeedback = findPreviousFeedback(sessionId, user);
		
		// Feedback can only be posted once. If there already is some feedback, we need to update it.
		if (!postedFeedback.isEmpty()) {
			for (Document f : postedFeedback) {
				// Use the first found feedback and update value and timestamp
				try {
					feedback = this.getDatabase().getDocument(f.getId());
					feedback.put("value", feedbackValueToString(value));
					feedback.put("timestamp", System.currentTimeMillis());
				} catch (IOException e) {
					return false;
				}
				break;
			}
		} else {
			feedback.put("type", "understanding");
			feedback.put("user", user.getUsername());
			feedback.put("sessionId", sessionId);
			feedback.put("timestamp", System.currentTimeMillis());
			feedback.put("value", feedbackValueToString(value));
		}
		
		try {
			this.getDatabase().saveDocument(feedback);
		} catch (IOException e) {
			return false;
		}
		
		return true;
	}
	
	private List<Document> findPreviousFeedback(String sessionId, de.thm.arsnova.entities.User user) {
		View view = new View("understanding/by_user");
		try {
			view.setKey(URLEncoder.encode("[\"" + sessionId + "\", \"" + user.getUsername() + "\"]", "UTF-8"));
		} catch(UnsupportedEncodingException e) {
			return Collections.<Document>emptyList();
		}
		ViewResults results = this.getDatabase().view(view);
		return results.getResults();
	}
	
	private String feedbackValueToString(int value) {
		switch (value) {
			case 0:
				return "Bitte schneller";
			case 1:
				return "Kann folgen";
			case 2:
				return "Zu schnell";
			case 3:
				return "Nicht mehr dabei";
			default:
				return null;
		}
	}

	@Override
	@Transactional(isolation=Isolation.READ_COMMITTED)
	public boolean sessionKeyAvailable(String keyword) {
		View view = new View("session/by_keyword");
		ViewResults results = this.getDatabase().view(view);
		
		return ! results.containsKey(keyword);
	}
	
	private String getSessionId(String keyword) {
		View view = new View("session/by_keyword");
		view.setKey(URLEncoder.encode("\"" + keyword + "\""));
		ViewResults results = this.getDatabase().view(view);

		if (results.getJSONArray("rows").optJSONObject(0) == null)
			return null;

		return results.getJSONArray("rows").optJSONObject(0)
				.optJSONObject("value").getString("_id");
	}
	
	private String getSessionKeyword(String internalSessionId) {
		try {
			View view = new View("session/by_id");
			view.setKey(URLEncoder.encode("\"" + internalSessionId + "\"", "UTF-8"));
			ViewResults results = this.getDatabase().view(view);
			for (Document d : results.getResults()) {
				Document arsSession = this.getDatabase().getDocument(d.getId());
				return arsSession.get("keyword").toString();
			}
		} catch (UnsupportedEncodingException e) {
			return null;
		} catch (IOException e) {
			return null;
		}
		return null;
	}

	private String currentTimestamp() {
		return Long.toString(System.currentTimeMillis());
	}
	
	private String actualUserName() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		User user = userService.getUser(authentication);
		if(user == null) return null;
		return user.getUsername();
	}

	private Database getDatabase() {
		if (database == null) {
			try {
				com.fourspaces.couchdb.Session session = new com.fourspaces.couchdb.Session(
						databaseHost,
						databasePort
					);
					
				database = session.getDatabase(databaseName);
			} catch (Exception e) {
				logger.error(
					"Cannot connect to CouchDB database '"
					+ databaseName
					+"' on host '"
					+ databaseHost
					+ "' using port "
					+ databasePort
				);
			}
		}
		
		return database;
	}

	@Override
	public boolean saveQuestion(Session session, Question question) {
		Document q = new Document();
		q.put("type", "skill_question");
		q.put("questionType", question.getQuestionType());
		q.put("sessionId", session.get_id());
		q.put("subject", question.getSubject());
		q.put("text", question.getText());
		q.put("active", question.isActive());
		q.put("number", 0); // TODO: This number has to get incremented automatically
		q.put("releasedFor", question.getReleasedFor());
		q.put("possibleAnswers", question.getPossibleAnswers());
		q.put("noCorrect", question.isNoCorrect());
		try {
			database.saveDocument(q);
		} catch (IOException e) {
			logger.error("Could not save question {}", question);
		}
		return false;
	}
}