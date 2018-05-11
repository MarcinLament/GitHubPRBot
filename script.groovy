#!/usr/bin/env groovy
import groovy.json.JsonSlurper
import java.util.regex.Pattern
import java.util.regex.Matcher

users = ["":""]

class Issue {
	Integer number
	String author
	String title
	String body
	HashMap<String, String> reviews
	List<String> labels
	List<String> requestedReviewers

	Issue(Integer number, String author, String title, String body, List<String> labels, List<String> requestedReviewers, HashMap<String, String> reviews) {
		this.number = number
		this.author = author
		this.title = title
		this.body = body
		this.labels = labels
		this.requestedReviewers = requestedReviewers
		this.reviews = reviews;
	}
}

githubUser = args[0]
githubRepo = args[1]
token = args[2]

def message = prepareMessage()
println message

def prepareMessage() {
	Issue[] issues = getIssues()
	def prsInProgress = 0
	def prsWithNotEnoughReviewers = ""
	def approvedWithoutQALabel = ""
	def missingJiraTicket = ""
	issues.each {
		prsInProgress += isPRInProgress(it)
		prsWithNotEnoughReviewers += hasNotEnoughReviewers(it)
		approvedWithoutQALabel += hasApprovedWithoutQALabel(it)
		missingJiraTicket += hasMissingJiraTicket(it)
	}

	def message = ""
	if (issues.size() > 0) {
		message += "There are *${issues.size()} open PRs* (*$prsInProgress in progress* and ${issues.size() - prsInProgress} in QA)"
		if (prsWithNotEnoughReviewers != "") {
			message += "\n\n>*Not enough reviewers:*" + prsWithNotEnoughReviewers
		}
		if (approvedWithoutQALabel != "") {
			message += "\n\n>*Approved without QA label:*" + approvedWithoutQALabel
		}
		if (missingJiraTicket != "") {
			message += "\n\n>*Missing JIRA ticket:*" + missingJiraTicket
		}

		message += "\n\n@here :point_up::point_up::point_up:"
	}
}

def hasMissingJiraTicket(Issue issue) {
	if ((issue.body =~ /([a-zA-Z]+-[0-9]+)/).getCount() == 0) {
		//def user = (users[issue.author] != null) ? users[issue.author] : issue.author
		return formatMessage(issue, mapUser(issue.author))
	}
	return ""
}

def hasApprovedWithoutQALabel(Issue issue) {
	def approved = true
	if (issue.reviews.size() > 1) {
		issue.reviews.each { key, value ->
			if (value != "APPROVED") {
				if (value != "COMMENTED" || (value == "COMMENTED" && issue.author != key)) {
					approved = false
					return false
				}
			}
		}
	} else {
		approved = false
	}
	
	if (approved && issue.requestedReviewers.size() == 0 && !issue.labels.contains("QA")) {
		//String user = (users[issue.author] != null) ? users[issue.author] : issue.author
		return formatMessage(issue, mapUser(issue.author))
	}
	return ""
}

def mapUser(String key) {
	String user = users[key]
	println "User: ${user}"
	if (user?.trim()) {
		return user
	}
	return key
}

def hasNotEnoughReviewers(Issue issue) {
	if ((issue.reviews.size() + issue.requestedReviewers.size() < 2) && !isWIP(issue)) {
		def reviewers = ""
		issue.reviews.each { key, value ->
			def user = (users[key] != null) ? users[key] : key
			reviewers += "${user.toString()} "
		}
		issue.requestedReviewers.each {
			def user = (users[it] != null) ? users[it] : it
			reviewers += "${user.toString()} "
		}
		return formatMessage(issue, (reviewers != "" ? reviewers : "no reviewers"))
	}
	return ""
}

def isPRInProgress(Issue issue) {
	return !issue.labels.contains("QA") ? 1 : 0
}

def isWIP(Issue issue) {
	return issue.labels.contains("WIP") ? 1 : 0
}

def getIssues() {
	def header = [Authorization: 'token ' + token]
	def url = getBaseUrl() + "issues?per_page=100"

	def json = url.toURL().getText(requestProperties: header)
	def jsonSlurper = new JsonSlurper()
	def object = jsonSlurper.parseText(json)

	def issues = []
	object.each {
		def labels = []
		def requestedReviewers = []
		it.labels.each {
			labels << it.name
		}
		it.requested_reviewers.each {
			requestedReviewers << it.login
		}

		def reviewersMap = null
		try {
			reviewersMap = getReviews(it.number)
		}
		catch(Exception ignore) {}

		issues << new Issue(it.number, it.user.login, it.title, it.body, labels, requestedReviewers, reviewersMap)
	}
	return issues
}

def getReviews(int issueNumber) {
	def header = [Authorization: 'token ' + token]
	def url = getBaseUrl() + "pulls/$issueNumber/reviews?per_page=100"

	def json = url.toURL().getText(requestProperties: header)
	def jsonSlurper = new JsonSlurper()
	def object = jsonSlurper.parseText(json)

	def reviewersMap = [:]
	object.each {
		reviewersMap.put(it.user.login, it.state)
	}
	return reviewersMap
}

def formatMessage(Issue issue, String user) {
	return "\n>• <https://github.com/$githubUser/$githubRepo/pull/$issue.number|$issue.number> | ${user}"
}

def formatIssueTitle(String title) {
	return title.substring(0, Math.min(title.length(), 20)) + "..."
}

def getBaseUrl() {
	return "https://api.github.com/repos/$githubUser/$githubRepo/"
}
