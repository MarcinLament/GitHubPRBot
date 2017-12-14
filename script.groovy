#!/usr/bin/env groovy
import groovy.json.JsonSlurper
import java.util.regex.Pattern
import java.util.regex.Matcher

baseUrl = ""
token = ""

users = ["jordanterry":"@jordan_terry",
"tomkricensky":"@tomas.kricensky",
"MarcinLament":"@marcin",
"AnkisCZ":"@lukas",
"Vanamas":"@vanamas",
"MatejVancik":"@matej"]

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

baseUrl = args[0]
token = args[1]

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

		message += "@here :point_up:"
	}
}

def hasMissingJiraTicket(Issue issue) {
	if ((issue.body =~ /([a-zA-Z]+-[0-9]+)/).getCount() == 0) {
		return formatMessage(issue, users[issue.author])
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
		return formatMessage(issue, users[issue.author])
	}
	return ""
}

def hasNotEnoughReviewers(Issue issue) {
	if (issue.reviews.size() + issue.requestedReviewers.size() < 2) {
		def reviewers = ""
		issue.reviews.each { key, value ->
			reviewers += "${users[key]} "
		}
		issue.requestedReviewers.each {
			reviewers += "${users[it]} "
		}
		return formatMessage(issue, (reviewers != "" ? reviewers : "no reviewers"))
	}
	return ""
}

def isPRInProgress(Issue issue) {
	return !issue.labels.contains("QA") ? 1 : 0
}

def getIssues() {
	def header = [Authorization: 'token ' + token]
	def url = baseUrl + "issues?per_page=100"

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
	def url = baseUrl + "pulls/$issueNumber/reviews?per_page=100"

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
	return "\n>• <https://github.com/ClearScore/caesium-android-v2/pull/$issue.number|$issue.number: ${formatIssueTitle(issue.title).padRight(21, "*")}> | ${user}"
}

def formatIssueTitle(String title) {
	return title.substring(0, Math.min(title.length(), 20)) + "&hellip;"
}
