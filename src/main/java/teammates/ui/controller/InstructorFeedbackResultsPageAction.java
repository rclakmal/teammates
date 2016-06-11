package teammates.ui.controller;

import teammates.common.datatransfer.FeedbackSessionAttributes;
import teammates.common.datatransfer.FeedbackSessionResultsBundle;
import teammates.common.datatransfer.InstructorAttributes;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.ExceedingRangeException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.Const.StatusMessageColor;
import teammates.common.util.StatusMessage;
import teammates.common.util.StringHelper;
import teammates.logic.api.GateKeeper;
import teammates.ui.controller.InstructorFeedbackResultsPageData.ViewType;

public class InstructorFeedbackResultsPageAction extends Action {

    private static final String ALL_SECTION_OPTION = "All";
    private static final int DEFAULT_QUERY_RANGE = 1000;
    private static final int DEFAULT_SECTION_QUERY_RANGE = 2500;
    private static final int QUERY_RANGE_FOR_AJAX_TESTING = 5;

    @Override
    protected ActionResult execute() throws EntityDoesNotExistException {

        String courseId = getRequestParamValue(Const.ParamsNames.COURSE_ID);
        String feedbackSessionName = getRequestParamValue(Const.ParamsNames.FEEDBACK_SESSION_NAME);
        Boolean isEmptyResponsesShown  = getRequestParamAsBoolean(Const.ParamsNames.FEEDBACK_RESULTS_INCLUDE_EMPTY_RESPONSES);
        Assumption.assertNotNull(courseId);
        Assumption.assertNotNull(feedbackSessionName);

        statusToAdmin = "Show instructor feedback result page<br>"
                      + "Session Name: " + feedbackSessionName + "<br>"
                      + "Course ID: " + courseId;

        InstructorAttributes instructor = logic.getInstructorForGoogleId(courseId, account.googleId);
        FeedbackSessionAttributes session = logic.getFeedbackSession(feedbackSessionName, courseId);
        boolean isCreatorOnly = true;

        new GateKeeper().verifyAccessible(instructor, session, !isCreatorOnly);

        InstructorFeedbackResultsPageData data = new InstructorFeedbackResultsPageData(account);
        String selectedSection = getRequestParamValue(Const.ParamsNames.FEEDBACK_RESULTS_GROUPBYSECTION);

        if (selectedSection == null) {
            selectedSection = ALL_SECTION_OPTION;
        }
        
        // this is for ajax loading of the html table in the modal
        // "(Non-English characters not displayed properly in the downloaded file? click here)"
        // TODO move into another action and another page data class
        boolean isLoadingCsvResultsAsHtml = getRequestParamAsBoolean(Const.ParamsNames.CSV_TO_HTML_TABLE_NEEDED);
        if (isLoadingCsvResultsAsHtml) {
            return createAjaxResultForCsvTableLoadedInHtml(courseId,
                                                           feedbackSessionName,
                                                           instructor,
                                                           data,
                                                           selectedSection);
        }
        data.setSessionResultsHtmlTableAsString("");
        data.setAjaxStatus("");
        
        String showStats = getRequestParamValue(Const.ParamsNames.FEEDBACK_RESULTS_SHOWSTATS);
        String groupByTeam = getRequestParamValue(Const.ParamsNames.FEEDBACK_RESULTS_GROUPBYTEAM);
        String sortType = getRequestParamValue(Const.ParamsNames.FEEDBACK_RESULTS_SORTTYPE);
        String startIndex = getRequestParamValue(Const.ParamsNames.FEEDBACK_RESULTS_MAIN_INDEX);

        
        if (startIndex != null) {
            data.setStartIndex(Integer.parseInt(startIndex));
        }

        if (sortType == null) {
            // default view: sort by question, statistics shown, grouped by team.
            showStats = "on";
            groupByTeam = "on";
            sortType = Const.FeedbackSessionResults.QUESTION_SORT_TYPE;
        }
        
        String questionId = getRequestParamValue(Const.ParamsNames.FEEDBACK_QUESTION_ID);
        String isTestingAjax = getRequestParamValue(Const.ParamsNames.FEEDBACK_RESULTS_NEED_AJAX);
        int queryRange = isTestingAjax == null ? DEFAULT_QUERY_RANGE : QUERY_RANGE_FOR_AJAX_TESTING;

        if (ALL_SECTION_OPTION.equals(selectedSection) && questionId == null
                && !Const.FeedbackSessionResults.QUESTION_SORT_TYPE.equals(sortType)) {
            // bundle for all questions and all sections
            data.setBundle(
                     logic.getFeedbackSessionResultsForInstructorWithinRangeFromView(
                                                                           feedbackSessionName, courseId,
                                                                           instructor.email,
                                                                           queryRange, sortType));
        } else if (Const.FeedbackSessionResults.QUESTION_SORT_TYPE.equals(sortType)) {
            data.setBundle(getBundleForQuestionView(isTestingAjax, courseId, feedbackSessionName, instructor, data,
                                                    selectedSection, sortType, questionId));
        } else if (Const.FeedbackSessionResults.GQR_SORT_TYPE.equals(sortType)
                || Const.FeedbackSessionResults.GRQ_SORT_TYPE.equals(sortType)) {
            data.setBundle(logic
                    .getFeedbackSessionResultsForInstructorFromSectionWithinRange(feedbackSessionName, courseId,
                                                                                  instructor.email,
                                                                                  selectedSection,
                                                                                  DEFAULT_SECTION_QUERY_RANGE));
        } else if (Const.FeedbackSessionResults.RQG_SORT_TYPE.equals(sortType)
                || Const.FeedbackSessionResults.RGQ_SORT_TYPE.equals(sortType)) {
            data.setBundle(logic
                    .getFeedbackSessionResultsForInstructorToSectionWithinRange(feedbackSessionName, courseId,
                                                                                instructor.email,
                                                                                selectedSection,
                                                                                DEFAULT_SECTION_QUERY_RANGE));
        }

        if (data.getBundle() == null) {
            throw new EntityDoesNotExistException("Feedback session " + feedbackSessionName
                                                  + " does not exist in " + courseId + ".");
        }

        // Warning for section wise viewing in case of many responses.
        boolean isShowSectionWarningForQuestionView = data.isLargeNumberOfRespondents()
                                                   && Const.FeedbackSessionResults.QUESTION_SORT_TYPE.equals(sortType);
        boolean isShowSectionWarningForParticipantView = !data.getBundle().isComplete
                                                   && !Const.FeedbackSessionResults.QUESTION_SORT_TYPE.equals(sortType);
        if (selectedSection.equals(ALL_SECTION_OPTION) && (isShowSectionWarningForParticipantView
                                                           || isShowSectionWarningForQuestionView)) {
            statusToUser.add(new StatusMessage(Const.StatusMessages.FEEDBACK_RESULTS_SECTIONVIEWWARNING,
                                               StatusMessageColor.WARNING));
            isError = true;
        }
        

        switch (sortType) {
        case Const.FeedbackSessionResults.QUESTION_SORT_TYPE:
            data.initForViewByQuestion(instructor, selectedSection, showStats, groupByTeam, isEmptyResponsesShown);
            return createShowPageResult(
                    Const.ViewURIs.INSTRUCTOR_FEEDBACK_RESULTS_BY_QUESTION, data);
        case Const.FeedbackSessionResults.RGQ_SORT_TYPE:
            data.initForSectionPanelViews(instructor, selectedSection, showStats, groupByTeam,
                                          ViewType.RECIPIENT_GIVER_QUESTION, isEmptyResponsesShown);
            return createShowPageResult(
                    Const.ViewURIs.INSTRUCTOR_FEEDBACK_RESULTS_BY_RECIPIENT_GIVER_QUESTION, data);
        case Const.FeedbackSessionResults.GRQ_SORT_TYPE:
            data.initForSectionPanelViews(instructor, selectedSection, showStats, groupByTeam,
                                          ViewType.GIVER_RECIPIENT_QUESTION, isEmptyResponsesShown);
            return createShowPageResult(
                    Const.ViewURIs.INSTRUCTOR_FEEDBACK_RESULTS_BY_GIVER_RECIPIENT_QUESTION, data);
        case Const.FeedbackSessionResults.RQG_SORT_TYPE:
            data.initForSectionPanelViews(instructor, selectedSection, showStats, groupByTeam,
                                          ViewType.RECIPIENT_QUESTION_GIVER, isEmptyResponsesShown);
            return createShowPageResult(
                    Const.ViewURIs.INSTRUCTOR_FEEDBACK_RESULTS_BY_RECIPIENT_QUESTION_GIVER, data);
        case Const.FeedbackSessionResults.GQR_SORT_TYPE:
            data.initForSectionPanelViews(instructor, selectedSection, showStats, groupByTeam,
                                          ViewType.GIVER_QUESTION_RECIPIENT, isEmptyResponsesShown);
            return createShowPageResult(
                    Const.ViewURIs.INSTRUCTOR_FEEDBACK_RESULTS_BY_GIVER_QUESTION_RECIPIENT, data);
        default:
            sortType = Const.FeedbackSessionResults.RGQ_SORT_TYPE;
            data.initForSectionPanelViews(instructor, selectedSection, showStats, groupByTeam,
                                          ViewType.RECIPIENT_GIVER_QUESTION, isEmptyResponsesShown);
            return createShowPageResult(
                    Const.ViewURIs.INSTRUCTOR_FEEDBACK_RESULTS_BY_RECIPIENT_GIVER_QUESTION, data);
        }
    }

    private FeedbackSessionResultsBundle getBundleForQuestionView(
            String needAjax, String courseId, String feedbackSessionName, InstructorAttributes instructor,
            InstructorFeedbackResultsPageData data, String selectedSection, String sortType, String questionId)
                    throws EntityDoesNotExistException {
        FeedbackSessionResultsBundle bundle;
        if (questionId == null) {
            if (ALL_SECTION_OPTION.equals(selectedSection)) {
                // load page structure without responses
                
                data.setLargeNumberOfRespondents(needAjax != null);
                
                // all sections and all questions for question view
                // set up question tables, responses to load by ajax
                bundle = logic.getFeedbackSessionResultsForInstructorWithinRangeFromView(
                                               feedbackSessionName, courseId,
                                               instructor.email,
                                               1, sortType);
                // set isComplete to true to prevent behavior when there are too many responses,
                // such as the display of warning messages
                bundle.isComplete = true;
            } else {
                // bundle for all questions, with a selected section
                bundle = logic.getFeedbackSessionResultsForInstructorInSection(feedbackSessionName, courseId,
                                                                                    instructor.email,
                                                                                    selectedSection);
            }
        } else {
            if (ALL_SECTION_OPTION.equals(selectedSection)) {
                // bundle for a specific question, with all sections
                bundle = logic.getFeedbackSessionResultsForInstructorFromQuestion(feedbackSessionName, courseId,
                                                                                  instructor.email, questionId);
            } else {
                // bundle for a specific question and a specific section
                bundle = logic.getFeedbackSessionResultsForInstructorFromQuestionInSection(
                                                feedbackSessionName, courseId,
                                                instructor.email, questionId, selectedSection);
            }
        }
        
        return bundle;
    }

    private ActionResult createAjaxResultForCsvTableLoadedInHtml(String courseId, String feedbackSessionName,
                                    InstructorAttributes instructor, InstructorFeedbackResultsPageData data,
                                    String selectedSection)
                                    throws EntityDoesNotExistException {
        try {
            if (selectedSection.contentEquals(ALL_SECTION_OPTION)) {
                data.setSessionResultsHtmlTableAsString(StringHelper.csvToHtmlTable(
                                            logic.getFeedbackSessionResultSummaryAsCsv(
                                                                            courseId,
                                                                            feedbackSessionName,
                                                                            instructor.email)));
            } else {
                data.setSessionResultsHtmlTableAsString(StringHelper.csvToHtmlTable(
                                            logic.getFeedbackSessionResultSummaryInSectionAsCsv(
                                                                            courseId,
                                                                            feedbackSessionName,
                                                                            instructor.email,
                                                                            selectedSection)));
            }
        } catch (ExceedingRangeException e) {
            // not tested as the test file is not large enough to reach this catch block
            data.setSessionResultsHtmlTableAsString("");
            data.setAjaxStatus("There are too many responses. Please download the feedback results by section.");
        }

        return createAjaxResult(data);
    }

}
