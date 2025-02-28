package org.example

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.niku.union.config.ConfigurationManager
import com.niku.union.config.properties.Database
import groovy.sql.Sql
import java.text.SimpleDateFormat
import java.sql.DriverManager

def runScript() {
    def objectCode = request.getParameter("objectCode")  // Get object code
    def projectId = request.getParameter("objectInstanceId")  // Get project ID
    def attribute = request.getParameter("attribute")
    def period = request.getParameter("timePeriod")  // Get the period (monthly, yearly, etc.)
    def units = request.getParameter("units") ?: "hours"  // Default to 'hours' if not provided
    def unitOfMeasure = request.getParameter("unitOfMeasure")  // Get the unit of measure (e.g., hours, days, etc.)
    def connection
    def sql
    def config = new Properties()
    def file = new File("application.properties")
    def username = config.getProperty("username")
    def password = config.getProperty("password")

    def periodMap = [
            "monthly"  : "months",
            "yearly"   : "years",
            "quarterly": "quarters",
            "weekly"   : "weeks"
    ]

    def laborMap = [
            "labor_act"       : "actualsCurve",
            "labor_alloc"     : "allocationCurve",
            "labor_etc"       : "estimatesCurve",
            "labor_hard_alloc": "hardAllocation",
            "labor_eac"       : "allocation",
            "cost_plans" : "costPlans"
    ]

    def periodType = periodMap[period.toLowerCase()] ?: "months"
    def laborType = laborMap[attribute] ?: "allocationCurve"

    def inputDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    def outputDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

    def startDate = ""
    def finishDate = ""

    try {
        Database database = ConfigurationManager.instance.properties.database.first()
        connection = DriverManager.getConnection(database.url, database.username, database.password)
        sql = new Sql(connection)

        def row = sql.firstRow("SELECT ii.schedule_start, ii.schedule_finish FROM inv_investments ii WHERE ii.id = ?", [projectId])

        if (row) {
            // Get the Timestamp values
            def startDateTimestamp = row.schedule_start
            def finishDateTimestamp = row.schedule_finish

            // Convert Timestamp to String
            def startDateStr = startDateTimestamp ? startDateTimestamp.toString() : ""
            def finishDateStr = finishDateTimestamp ? finishDateTimestamp.toString() : ""

            def parsedStartDate = inputDateFormat.parse(startDateStr)
            def parsedFinishDate = inputDateFormat.parse(finishDateStr)

            startDate = outputDateFormat.format(parsedStartDate)
            finishDate = outputDateFormat.format(parsedFinishDate)
        } else {
            throw new Exception("No project data found for ID: ${projectId}")
        }
        sql.close()
        // API URL construction
        def apiUrl
        if (objectCode == "project" && laborType != "costPlans") {
            apiUrl = "http://10.0.0.173:7080/ppm/rest/v1/projects/${projectId}/teams?fields=resource,${laborType}&tsvParams=(periods=(${laborType},${periodType},12,${startDate},fiscal)),(workEffortUnit=${unitOfMeasure})"
        } else if (objectCode == "idea" && laborType != "costPlans") {
            apiUrl = "http://10.0.0.173:7080/ppm/rest/v1/ideas/${projectId}/teams?fields=resource,${laborType}&tsvParams=(periods=(${laborType},${periodType},12,${startDate},fiscal)),(workEffortUnit=${unitOfMeasure})"
        }

        def urlConnection = new URL(apiUrl).openConnection() as HttpURLConnection
        urlConnection.setRequestMethod("GET")


        // Handle Authentication if needed (for basic auth in headers)
        def auth = "${username}:${password}".bytes.encodeBase64().toString()
        urlConnection.setRequestProperty("Authorization", "Basic ${auth}")
        urlConnection.setRequestProperty("Content-Type", "application/json")

        // Check the response code before reading the response
        if (urlConnection.responseCode != 200) {
            throw new IOException("Failed to get a valid response. HTTP Response Code: ${urlConnection.responseCode}")
        }

        // Read the response
        def response = urlConnection.inputStream.text
        def jsonResponse = new JsonSlurper().parseText(response)

        // Check for empty response or errors
        if (!jsonResponse._results || jsonResponse._results.isEmpty()) {
            println "No data found or there was an error with the request."
            return JsonOutput.toJson([message: "No data found"])
        }

        // Returning the fetched response as a JSON object
        return JsonOutput.toJson(jsonResponse)

    } catch (Exception e) {
        // Handle any exceptions
        println "An error occurred: ${e.message}"
        return JsonOutput.toJson([message: "Error occurred", error: e.message])
    } finally {
        // Ensure the resources are closed
        try {
            if (connection != null) connection.close()
        } catch (Exception e) {
            println "Error closing the connection: ${e.message}"
        }
    }
}

println runScript()
