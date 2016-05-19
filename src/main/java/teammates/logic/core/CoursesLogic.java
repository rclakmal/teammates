package teammates.logic.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import teammates.common.datatransfer.AccountAttributes;
import teammates.common.datatransfer.CourseAttributes;
import teammates.common.datatransfer.CourseDetailsBundle;
import teammates.common.datatransfer.CourseSummaryBundle;
import teammates.common.datatransfer.FeedbackSessionAttributes;
import teammates.common.datatransfer.FeedbackSessionDetailsBundle;
import teammates.common.datatransfer.InstructorAttributes;
import teammates.common.datatransfer.InstructorPrivileges;
import teammates.common.datatransfer.SectionDetailsBundle;
import teammates.common.datatransfer.StudentAttributes;
import teammates.common.datatransfer.TeamDetailsBundle;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.TeammatesException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.FieldValidator;
import teammates.common.util.Sanitizer;
import teammates.common.util.StringHelper;
import teammates.common.util.Utils;
import teammates.storage.api.CoursesDb;

/**
 * Handles operations related to courses.
 */
public class CoursesLogic {
    /* Explanation: Most methods in the API of this class doesn't have header 
     *  comments because it sits behind the API of the logic class. 
     *  Those who use this class is expected to be familiar with the its code 
     *  and Logic's code. Hence, no need for header comments.
     */ 
    
    //TODO: There's no need for this class to be a Singleton.
    private static CoursesLogic instance = null;
    
    private static final Logger log = Utils.getLogger();
    
    /* Explanation: This class depends on CoursesDb class but no other *Db classes.
     * That is because reading/writing entities from/to the datastore is the 
     * responsibility of the matching *Logic class.
     * However, this class can talk to other *Logic classes. That is because
     * the logic related to one entity type can involve the logic related to
     * other entity types.
     */

    private static final CoursesDb coursesDb = new CoursesDb();
    
    private static final StudentsLogic studentsLogic = StudentsLogic.inst();
    private static final InstructorsLogic instructorsLogic = InstructorsLogic.inst();
    private static final AccountsLogic accountsLogic = AccountsLogic.inst();
    private static final FeedbackSessionsLogic feedbackSessionsLogic = FeedbackSessionsLogic.inst();
    private static final CommentsLogic commentsLogic = CommentsLogic.inst();

    
    public static CoursesLogic inst() {
        if (instance == null)
            instance = new CoursesLogic();
        return instance;
    }

    public void createCourse(String courseId, String courseName) throws InvalidParametersException, 
                                                                        EntityAlreadyExistsException {
        
        CourseAttributes courseToAdd = new CourseAttributes(courseId, courseName);
        coursesDb.createEntity(courseToAdd);
    }
    
    /**
     * Creates a Course object and an Instructor object for the Course.
     */
    public void createCourseAndInstructor(String instructorGoogleId, String courseId, String courseName) 
            throws InvalidParametersException, EntityAlreadyExistsException {
        
        AccountAttributes courseCreator = accountsLogic.getAccount(instructorGoogleId);
        Assumption.assertNotNull("Trying to create a course for a non-existent instructor :" + instructorGoogleId, 
                                 courseCreator);
        Assumption.assertTrue("Trying to create a course for a person who doesn't have instructor privileges :" + instructorGoogleId, 
                              courseCreator.isInstructor);
        
        createCourse(courseId, courseName);
        
        /* Create the initial instructor for the course */
        InstructorPrivileges privileges = new InstructorPrivileges(
                Const.InstructorPermissionRoleNames.INSTRUCTOR_PERMISSION_ROLE_COOWNER);
        InstructorAttributes instructor = new InstructorAttributes(
                instructorGoogleId, 
                courseId, 
                courseCreator.name, 
                courseCreator.email, 
                Const.InstructorPermissionRoleNames.INSTRUCTOR_PERMISSION_ROLE_COOWNER, 
                true, 
                InstructorAttributes.DEFAULT_DISPLAY_NAME,
                privileges);
        
        try {
            instructorsLogic.createInstructor(instructor);
        } catch (EntityAlreadyExistsException | InvalidParametersException e) {
            //roll back the transaction
            coursesDb.deleteCourse(courseId);
            String errorMessage = "Unexpected exception while trying to create instructor for a new course " + Const.EOL 
                                  + instructor.toString() + Const.EOL
                                  + TeammatesException.toStringWithStackTrace(e);
            Assumption.fail(errorMessage);
        }
    }

    /**
     * Get the {@link CourseAttributes} using the courseId.
     * @param courseId
     * @return {@link CourseAttributes}
     */
    public CourseAttributes getCourse(String courseId) {
        return coursesDb.getCourse(courseId);
    }

    /**
     * Checks whether course is present using courseId.
     * @param courseId
     * @return {@code True} if course is present
     */
    public boolean isCoursePresent(String courseId) {
        return coursesDb.getCourse(courseId) != null;
    }
    
    /**
     * Checks whether the course is a sample course using the courseId.
     * @param courseId
     * @return {@code True} if course is a sample course
     */
    public boolean isSampleCourse(String courseId) {
        Assumption.assertNotNull("Course ID is null", courseId);
        return StringHelper.isMatching(courseId, FieldValidator.REGEX_SAMPLE_COURSE_ID);
    }

    /**
     * Used to trigger an {@link EntityDoesNotExistException} if the course is not present. 
     * @param courseId
     * @throws EntityDoesNotExistException
     */
    public void verifyCourseIsPresent(String courseId) throws EntityDoesNotExistException {
        if (!isCoursePresent(courseId)) {
            throw new EntityDoesNotExistException("Course does not exist: " + courseId);
        }
    }

    public CourseDetailsBundle getCourseDetails(String courseId) throws EntityDoesNotExistException {
        CourseDetailsBundle courseSummary = getCourseSummary(courseId);
        return courseSummary;
    }

    /**
     * Returns a list of {@link CourseDetailsBundle} for all courses a given student is enrolled in. 
     * @param googleId googleId of the student
     * @return a list of {@link CourseDetailsBundle}
     * @throws EntityDoesNotExistException
     */
    public List<CourseDetailsBundle> getCourseDetailsListForStudent(String googleId) 
                throws EntityDoesNotExistException {
        
        List<CourseAttributes> courseList = getCoursesForStudentAccount(googleId);
        List<CourseDetailsBundle> courseDetailsList = new ArrayList<CourseDetailsBundle>();
        
        for (CourseAttributes c : courseList) {

            StudentAttributes s = studentsLogic.getStudentForCourseIdAndGoogleId(c.id, googleId);
            
            if (s == null) {
                //TODO Remove excessive logging after the reason why s can be null is found
                String logMsg = "Student is null in CoursesLogic.getCourseDetailsListForStudent(String googleId)"
                                + "<br/> Student Google ID: " + googleId + "<br/> Course: " + c.id
                                + "<br/> All Courses Retrieved using the Google ID:";
                for (CourseAttributes course : courseList) {
                    logMsg += "<br/>" + course.id;
                }
                log.severe(logMsg);
                
                //TODO Failing might not be the best course of action here. 
                //Maybe throw a custom exception and tell user to wait due to eventual consistency?
                Assumption.assertNotNull("Student should not be null at this point.", s);
            }
            
            // Skip the course existence check since the course ID is obtained from a
            // valid CourseAttributes resulting from query
            List<FeedbackSessionAttributes> feedbackSessionList = 
                    feedbackSessionsLogic.getFeedbackSessionsForUserInCourseSkipCheck(c.id, s.email);

            CourseDetailsBundle cdd = new CourseDetailsBundle(c);
            
            for (FeedbackSessionAttributes fs : feedbackSessionList) {
                cdd.feedbackSessions.add(new FeedbackSessionDetailsBundle(fs));
            }
            
            courseDetailsList.add(cdd);
        }
        return courseDetailsList;
    }

    /**
     * Get a list of section names for a course using the courseId.
     * @param courseId
     * @return a list of section names
     * @throws EntityDoesNotExistException
     */
    public List<String> getSectionsNameForCourse(String courseId) throws EntityDoesNotExistException {
        return getSectionsNameForCourse(courseId, false);  
    }

    /**
     * Get a list of section names for a course using the {@link CourseAttributes}.
     * @param course
     * @return a list of section names
     * @throws EntityDoesNotExistException
     */
    public List<String> getSectionsNameForCourse(CourseAttributes course) throws EntityDoesNotExistException {
        Assumption.assertNotNull("Course is null", course);
        return getSectionsNameForCourse(course.id, true);
    }
    
    /**
     * Get list of section names for a course with or without a need to check if the course is existent
     * @param courseId Course ID of the course
     * @param hasCheckIsPresent Determine whether it is necessary to check if the course exists
     * @return list of sections names from the specified course
     * @throws EntityDoesNotExistException
     */
    private List<String> getSectionsNameForCourse(String courseId, boolean isCourseVerified) 
        throws EntityDoesNotExistException {
        if (!isCourseVerified) {
            verifyCourseIsPresent(courseId);    
        }
        List<StudentAttributes> studentDataList = studentsLogic.getStudentsForCourse(courseId);
        
        Set<String> sectionNameSet = new HashSet<String>();
        for (StudentAttributes sd: studentDataList) {
            if (!sd.section.equals(Const.DEFAULT_SECTION)) {
                sectionNameSet.add(sd.section);
            }
        }
        
        List<String> sectionNameList = new ArrayList<String>(sectionNameSet);
        Collections.sort(sectionNameList);

        return sectionNameList;   
    }

    public SectionDetailsBundle getSectionForCourse(String section, String courseId)
            throws EntityDoesNotExistException {

        verifyCourseIsPresent(courseId);
        
        List<StudentAttributes> students = studentsLogic.getStudentsForSection(section, courseId);
        StudentAttributes.sortByTeamName(students);

        SectionDetailsBundle sectionDetails = new SectionDetailsBundle();
        TeamDetailsBundle team = null;
        sectionDetails.name = section;
        for (int i = 0; i < students.size(); i++) {
            StudentAttributes s = students.get(i);
    
            // first student of first team
            if (team == null) {
                team = new TeamDetailsBundle();
                team.name = s.team;
                team.students.add(s);
            } 
            // student in the same team as the previous student
            else if (s.team.equals(team.name)) {
                team.students.add(s);
            } 
            // first student of subsequent teams (not the first team)
            else {
                sectionDetails.teams.add(team);
                team = new TeamDetailsBundle();
                team.name = s.team;
                team.students.add(s);
            }
    
            // if last iteration
            if (i == students.size() - 1) {
                sectionDetails.teams.add(team);
            }
        }
        return sectionDetails;
    }
    
    /**
     * Gets a list of {@link SectionDetailsBundle} for a given course using course attributes and course details bundle.
     * @param course {@link CourseAttributes}
     * @param cdd {@link CourseDetailsBundle}
     * @return a list of {@link SectionDetailsBundle}
     */
    public List<SectionDetailsBundle> getSectionsForCourse(CourseAttributes course, CourseDetailsBundle cdd) {
        Assumption.assertNotNull("Course is null", course);
        
        List<StudentAttributes> students = studentsLogic.getStudentsForCourse(course.id);
        StudentAttributes.sortBySectionName(students);
        
        List<SectionDetailsBundle> sections = new ArrayList<SectionDetailsBundle>();
        
        SectionDetailsBundle section = null;
        int teamIndexWithinSection = 0;
        
        for (int i = 0; i < students.size(); i++) {
            
            StudentAttributes s = students.get(i);
            cdd.stats.studentsTotal++;
            if (!s.isRegistered()) {
                cdd.stats.unregisteredTotal++;
            }
            
            if (section == null) {   // First student of first section
                section = new SectionDetailsBundle();
                section.name = s.section;
                section.teams.add(new TeamDetailsBundle());
                cdd.stats.teamsTotal++;
                section.teams.get(teamIndexWithinSection).name = s.team;
                section.teams.get(teamIndexWithinSection).students.add(s);
            } else if (s.section.equals(section.name)) {
                if (s.team.equals(section.teams.get(teamIndexWithinSection).name)) {
                    section.teams.get(teamIndexWithinSection).students.add(s);
                } else {
                    teamIndexWithinSection++;
                    section.teams.add(new TeamDetailsBundle());
                    cdd.stats.teamsTotal++;
                    section.teams.get(teamIndexWithinSection).name = s.team;
                    section.teams.get(teamIndexWithinSection).students.add(s);
                }
            } else { // first student of subsequent section
                sections.add(section);
                if (!section.name.equals(Const.DEFAULT_SECTION)) {
                    cdd.stats.sectionsTotal++;
                }
                teamIndexWithinSection = 0;
                section = new SectionDetailsBundle();
                section.name = s.section;
                section.teams.add(new TeamDetailsBundle());
                cdd.stats.teamsTotal++;
                section.teams.get(teamIndexWithinSection).name = s.team;
                section.teams.get(teamIndexWithinSection).students.add(s);
            }
            
            boolean isLastStudent = i == students.size() - 1;
            if (isLastStudent) {
                sections.add(section);
                if (!section.name.equals(Const.DEFAULT_SECTION)) {
                    cdd.stats.sectionsTotal++;
                }
            }
        }
        
        return sections;
    }
    
    /**
     * Get a list of {@link SectionDetailsBundle} for a given course using courseId.
     * @param courseId
     * @return a list of {@link SectionDetailsBundle}
     * @throws EntityDoesNotExistException
     */
    public List<SectionDetailsBundle> getSectionsForCourseWithoutStats(String courseId) 
            throws EntityDoesNotExistException {
        
        verifyCourseIsPresent(courseId);
        
        List<StudentAttributes> students = studentsLogic.getStudentsForCourse(courseId);
        StudentAttributes.sortBySectionName(students);
        
        List<SectionDetailsBundle> sections = new ArrayList<SectionDetailsBundle>();
        
        SectionDetailsBundle section = null;
        int teamIndexWithinSection = 0;
        
        for (int i = 0; i < students.size(); i++) {
            StudentAttributes s = students.get(i);
            
            if (section == null) {   // First student of first section
                section = new SectionDetailsBundle();
                section.name = s.section;
                section.teams.add(new TeamDetailsBundle());
                section.teams.get(teamIndexWithinSection).name = s.team;
                section.teams.get(teamIndexWithinSection).students.add(s);
            } else if (s.section.equals(section.name)) {
                if (s.team.equals(section.teams.get(teamIndexWithinSection).name)) {
                    section.teams.get(teamIndexWithinSection).students.add(s);
                } else {
                    teamIndexWithinSection++;
                    section.teams.add(new TeamDetailsBundle());
                    section.teams.get(teamIndexWithinSection).name = s.team;
                    section.teams.get(teamIndexWithinSection).students.add(s);
                }
            } else { // first student of subsequent section
                sections.add(section);
                teamIndexWithinSection = 0;
                section = new SectionDetailsBundle();
                section.name = s.section;
                section.teams.add(new TeamDetailsBundle());
                section.teams.get(teamIndexWithinSection).name = s.team;
                section.teams.get(teamIndexWithinSection).students.add(s);
            }
            
            boolean isLastStudent = i == students.size() - 1;
            if (isLastStudent) {
                sections.add(section);
            }
        }
        
        return sections;
    }

    /**
     * Returns Teams for a particular courseId.<br> 
     * <b>Note:</b><br>
     * This method does not returns any Loner information presently,<br>
     * Loner information must be returned as we decide to support loners<br>in future.
     *  
     */
    public List<TeamDetailsBundle> getTeamsForCourse(String courseId) throws EntityDoesNotExistException {

        if (getCourse(courseId) == null) {
            throw new EntityDoesNotExistException("The course " + courseId + " does not exist");
        }
    
        List<StudentAttributes> students = studentsLogic.getStudentsForCourse(courseId);
        StudentAttributes.sortByTeamName(students);
        
        List<TeamDetailsBundle> teams = new ArrayList<TeamDetailsBundle>(); 
        
        TeamDetailsBundle team = null;
        
        for (int i = 0; i < students.size(); i++) {
    
            StudentAttributes s = students.get(i);
    
            // first student of first team
            if (team == null) {
                team = new TeamDetailsBundle();
                team.name = s.team;
                team.students.add(s);
            } 
            // student in the same team as the previous student
            else if (s.team.equals(team.name)) {
                team.students.add(s);
            } 
            // first student of subsequent teams (not the first team)
            else {
                teams.add(team);
                team = new TeamDetailsBundle();
                team.name = s.team;
                team.students.add(s);
            }
    
            // if last iteration
            if (i == students.size() - 1) {
                teams.add(team);
            }
        }
    
        return teams;
    }

    public int getNumberOfSections(String courseID) throws EntityDoesNotExistException {
        List<String> sectionNameList = getSectionsNameForCourse(courseID);
        return sectionNameList.size();
    }

    public int getNumberOfTeams(String courseID) throws EntityDoesNotExistException {
        verifyCourseIsPresent(courseID);
        List<StudentAttributes> studentDataList = studentsLogic.getStudentsForCourse(courseID);

        List<String> teamNameList = new ArrayList<String>();

        for (StudentAttributes sd : studentDataList) {
            if (!teamNameList.contains(sd.team)) {
                teamNameList.add(sd.team);
            }
        }

        return teamNameList.size();
    }

    public int getTotalEnrolledInCourse(String courseId) throws EntityDoesNotExistException {
        verifyCourseIsPresent(courseId);
        return studentsLogic.getStudentsForCourse(courseId).size();
    }

    public int getTotalUnregisteredInCourse(String courseId) throws EntityDoesNotExistException {
        verifyCourseIsPresent(courseId);
        return studentsLogic.getUnregisteredStudentsForCourse(courseId).size();
    }

    /**
     * Gets the {@link CourseDetailsBundle} for a course using {@link CourseAttributes}.
     * @param courseAttributes
     * @return {@link CourseDetailsBundle}
     * @throws EntityDoesNotExistException
     */
    public CourseDetailsBundle getCourseSummary(CourseAttributes courseAttributes) throws EntityDoesNotExistException {
        Assumption.assertNotNull("Supplied parameter was null\n", courseAttributes);
        
        CourseDetailsBundle cdd = new CourseDetailsBundle(courseAttributes);
        cdd.sections = (ArrayList<SectionDetailsBundle>) getSectionsForCourse(courseAttributes, cdd);
        
        return cdd;
    }
    
    // TODO: reduce calls to this function, use above function instead.
    /**
     * Gets the {@link CourseDetailsBundle} for a course using courseId.
     * @param courseId
     * @return {@link CourseDetailsBundle}
     * @throws EntityDoesNotExistException
     */
    public CourseDetailsBundle getCourseSummary(String courseId) throws EntityDoesNotExistException {
        CourseAttributes cd = coursesDb.getCourse(courseId);

        if (cd == null) {
            throw new EntityDoesNotExistException("The course does not exist: " + courseId);
        }
        
        return getCourseSummary(cd);
    }
    
    /**
     * Get the mapped course data, including its feedback sessions using the given {@link InstructorAttributes}.
     * @param instructor
     * @return {@link CourseSummaryBundle}
     * @throws EntityDoesNotExistException
     */
    public CourseSummaryBundle getCourseSummaryWithFeedbackSessionsForInstructor(
            InstructorAttributes instructor) throws EntityDoesNotExistException {
        CourseSummaryBundle courseSummary = getCourseSummaryWithoutStats(instructor.courseId);
        courseSummary.feedbackSessions.addAll(feedbackSessionsLogic.getFeedbackSessionListForInstructor(instructor));
        return courseSummary;
    }

    /**
     * Get the {@link CourseSummaryBundle} using the {@link CourseAttributes}.
     * @param courseAttributes
     * @return {@link CourseSummaryBundle}
     * @throws EntityDoesNotExistException
     */
    public CourseSummaryBundle getCourseSummaryWithoutStats(CourseAttributes courseAttributes) throws EntityDoesNotExistException {
        Assumption.assertNotNull("Supplied parameter was null\n", courseAttributes);

        CourseSummaryBundle cdd = new CourseSummaryBundle(courseAttributes);
        return cdd;
    }
    
    /**
     * Get the {@link CourseSummaryBundle} using the courseId.
     * @param courseAttributes
     * @return {@link CourseSummaryBundle}
     * @throws EntityDoesNotExistException
     */
    public CourseSummaryBundle getCourseSummaryWithoutStats(String courseId) throws EntityDoesNotExistException {
        CourseAttributes cd = coursesDb.getCourse(courseId);

        if (cd == null) {
            throw new EntityDoesNotExistException("The course does not exist: " + courseId);
        }

        return getCourseSummaryWithoutStats(cd);
    }
    
    /**
     * Returns a list of {@link CourseAttributes} for all courses a given student is enrolled in. 
     * @param googleId googleId of the student
     * @return a list of {@link CourseAttributes}
     * @throws EntityDoesNotExistException
     */
    public List<CourseAttributes> getCoursesForStudentAccount(String googleId) throws EntityDoesNotExistException {
        List<StudentAttributes> studentDataList = studentsLogic.getStudentsForGoogleId(googleId);
        
        if (studentDataList.size() == 0) {
            throw new EntityDoesNotExistException("Student with Google ID " + googleId + " does not exist");
        }
        
        List<String> courseIds = new ArrayList<String>();
        for (StudentAttributes s : studentDataList) {
            courseIds.add(s.course);
        }
        List<CourseAttributes> courseList = coursesDb.getCourses(courseIds);
        
        return courseList;
    }

    /**
     * Returns a list of {@link CourseAttributes} for all courses a given instructor is mapped to. 
     * @param googleId googleId of the instructor
     * @return a list of {@link CourseAttributes}
     * @throws EntityDoesNotExistException
     */
    public List<CourseAttributes> getCoursesForInstructor(String googleId) throws EntityDoesNotExistException {
        return getCoursesForInstructor(googleId, false);
    }
    
    /**
     * Returns a list of {@link CourseAttributes} for courses a given instructor is mapped to.
     * @param googleId googleId of the instructor
     * @param omitArchived if {@code True} omits all the archived courses from the return
     * @return a list of {@link CourseAttributes}
     * @throws EntityDoesNotExistException
     */
    public List<CourseAttributes> getCoursesForInstructor(String googleId, boolean omitArchived) 
            throws EntityDoesNotExistException {
        List<InstructorAttributes> instructorList = instructorsLogic.getInstructorsForGoogleId(googleId, omitArchived);
        return getCoursesForInstructor(instructorList);
    }
    
    /**
     * Gets a list of {@link CourseAttributes} for all courses for a given list of instructors. 
     * @param instructorList
     * @return a list of {@link CourseAttributes}
     * @throws EntityDoesNotExistException
     */
    public List<CourseAttributes> getCoursesForInstructor(List<InstructorAttributes> instructorList)
            throws EntityDoesNotExistException {
        Assumption.assertNotNull("Supplied parameter was null\n", instructorList);
        List<String> courseIdList = new ArrayList<String>();

        for (InstructorAttributes instructor : instructorList) {
            courseIdList.add(instructor.courseId);
        }
        
        List<CourseAttributes> courseList = coursesDb.getCourses(courseIdList);
        
        // Check that all courseIds queried returned a course.
        if (courseIdList.size() > courseList.size()) {
            for (CourseAttributes ca : courseList) {
                courseIdList.remove(ca.id);
            }
            log.severe("Course(s) was deleted but the instructor still exists: " + Const.EOL + courseIdList.toString());
        }
        
        return courseList;
    }
    
    /**
     * Gets course summaries for instructor.<br>
     * Omits archived courses if omitArchived == true<br>
     * 
     * @param googleId
     * @return HashMap with courseId as key, and CourseDetailsBundle as value.
     * Does not include details within the course, such as feedback sessions.
     */
    public HashMap<String, CourseDetailsBundle> getCourseSummariesForInstructor(String googleId, boolean omitArchived) 
            throws EntityDoesNotExistException {
        
        instructorsLogic.verifyInstructorExists(googleId);

        List<InstructorAttributes> instructorAttributesList = instructorsLogic.getInstructorsForGoogleId(googleId, 
                                                                                                         omitArchived);
        
        return getCourseSummariesForInstructor(instructorAttributesList);
    }
    
    /**
     * Gets course summaries for instructors.<br>
     * 
     * @param instructorAttributesList
     * @return HashMap with courseId as key, and CourseDetailsBundle as value.
     * Does not include details within the course, such as feedback sessions.
     */
    public HashMap<String, CourseDetailsBundle> getCourseSummariesForInstructor(List<InstructorAttributes> instructorAttributesList) 
            throws EntityDoesNotExistException {
        
        HashMap<String, CourseDetailsBundle> courseSummaryList = new HashMap<String, CourseDetailsBundle>();
        List<String> courseIdList = new ArrayList<String>();
        
        for (InstructorAttributes instructor : instructorAttributesList) {
            courseIdList.add(instructor.courseId);
        }
        
        List<CourseAttributes> courseList = coursesDb.getCourses(courseIdList);
        
        // Check that all courseIds queried returned a course.
        if (courseIdList.size() > courseList.size()) {
            for (CourseAttributes ca : courseList) {
                courseIdList.remove(ca.id);
            }
            log.severe("Course(s) was deleted but the instructor still exists: " + Const.EOL + courseIdList.toString());
        }
        
        for (CourseAttributes ca : courseList) {
            courseSummaryList.put(ca.id, getCourseSummary(ca));
        }
        
        return courseSummaryList;
    }
 
    /**
     * Gets course details list for instructor.<br>
     * Omits archived courses if omitArchived == true<br>
     * 
     * @param instructorId - Google Id of instructor
     * @return HashMap with courseId as key, and CourseDetailsBundle as value.
     **/
    public HashMap<String, CourseDetailsBundle> getCoursesDetailsListForInstructor(String instructorId, 
                                                                                   boolean omitArchived) 
           throws EntityDoesNotExistException {
        
        HashMap<String, CourseDetailsBundle> courseList = 
                getCourseSummariesForInstructor(instructorId, omitArchived);
        
        // TODO: remove need for lower level functions to make repeated db calls
        // getFeedbackSessionDetailsForInstructor
        // The above functions make repeated calls to get InstructorAttributes
        List<FeedbackSessionDetailsBundle> feedbackSessionList = 
                feedbackSessionsLogic.getFeedbackSessionDetailsForInstructor(instructorId, omitArchived);
        
        for (FeedbackSessionDetailsBundle fsb : feedbackSessionList) {
            CourseDetailsBundle courseSummary = courseList.get(fsb.feedbackSession.courseId);
            if (courseSummary != null) {
                courseSummary.feedbackSessions.add(fsb);
            }
        }
        return courseList;
    }
    
    /**
     * Get a Map<CourseId, {@link CourseSummaryBundle} for all courses mapped to a given instructor.
     * @param instructorId
     * @param omitArchived if {@code True} omits all the archived courses from the return
     * @return a Map<CourseId, {@link CourseSummaryBundle}
     * @throws EntityDoesNotExistException
     */
    public HashMap<String, CourseSummaryBundle> getCoursesSummaryWithoutStatsForInstructor(
            String instructorId, boolean omitArchived) throws EntityDoesNotExistException {
        
        List<InstructorAttributes> instructorList = instructorsLogic.getInstructorsForGoogleId(instructorId, 
                                                                                               omitArchived);
        HashMap<String, CourseSummaryBundle> courseList = getCourseSummaryWithoutStatsForInstructor(instructorList);
        return courseList;
    }
    
    // TODO: batch retrieve courses?
    /**
     * Get a list of {@link CourseAttributes} for all archived courses mapped to an insturctor.
     * @param googleId
     * @return a list of {@link CourseAttributes}
     * @throws EntityDoesNotExistException
     */
    public List<CourseAttributes> getArchivedCoursesForInstructor(String googleId) throws EntityDoesNotExistException {
        
        List<InstructorAttributes> instructorList = instructorsLogic.getInstructorsForGoogleId(googleId);
        
        ArrayList<CourseAttributes> courseList = new ArrayList<CourseAttributes>();

        for (InstructorAttributes instructor : instructorList) {
            CourseAttributes course = coursesDb.getCourse(instructor.courseId);
            
            if (course == null) {
                log.warning("Course was deleted but the Instructor still exists: " + Const.EOL 
                            + instructor.toString());
            } else {
                boolean isCourseArchived = isCourseArchived(instructor.courseId, instructor.googleId);
                if (isCourseArchived) {
                    courseList.add(course);
                }
            }
        }
        
        return courseList;
    }
    
    /**
     * Updates the 'archived' status of a course using the courseId.
     * @param courseId
     * @param archiveStatus if {@code True} course is set to "archived".
     * @throws InvalidParametersException
     * @throws EntityDoesNotExistException
     */
    public void setArchiveStatusOfCourse(String courseId, boolean archiveStatus) throws InvalidParametersException, 
                                                                                        EntityDoesNotExistException {
        
        CourseAttributes courseToUpdate = getCourse(courseId);
        if (courseToUpdate != null) {
            courseToUpdate.isArchived = archiveStatus;
            coursesDb.updateCourse(courseToUpdate);
        } else {
            throw new EntityDoesNotExistException("Course does not exist: " + courseId);
        }
    }
    
    /**
     * Updates the course details.
     * @param newCourse the course object containing new details of the course
     * @throws InvalidParametersException
     * @throws EntityDoesNotExistException
     */
    public void updateCourse(CourseAttributes newCourse) throws InvalidParametersException, 
                                                                EntityDoesNotExistException {
        Assumption.assertNotNull(Const.StatusCodes.NULL_PARAMETER, newCourse);
        
        CourseAttributes oldCourse = coursesDb.getCourse(newCourse.getId());
        
        if (oldCourse == null) {
            throw new EntityDoesNotExistException("Trying to update a course that does not exist.");
        }
        
        coursesDb.updateCourse(newCourse);
    }

    /**
     * Delete a course from its given corresponding ID
     * This will also cascade the data in other databases which are related to this course
     */ 
    public void deleteCourseCascade(String courseId) {
        studentsLogic.deleteStudentsForCourse(courseId);
        instructorsLogic.deleteInstructorsForCourse(courseId);
        commentsLogic.deleteCommentsForCourse(courseId);
        feedbackSessionsLogic.deleteFeedbackSessionsForCourseCascade(courseId);
        coursesDb.deleteCourse(courseId);
    }
    
    private HashMap<String, CourseSummaryBundle> getCourseSummaryWithoutStatsForInstructor(
            List<InstructorAttributes> instructorAttributesList) throws EntityDoesNotExistException {
        
        HashMap<String, CourseSummaryBundle> courseSummaryList = new HashMap<String, CourseSummaryBundle>();
        
        List<String> courseIdList = new ArrayList<String>();
        
        for (InstructorAttributes ia : instructorAttributesList) {
            courseIdList.add(ia.courseId);
        }
        List<CourseAttributes> courseList = coursesDb.getCourses(courseIdList);
        
        // Check that all courseIds queried returned a course.
        if (courseIdList.size() > courseList.size()) {
            for (CourseAttributes ca : courseList) {
                courseIdList.remove(ca.id);
            }
            log.severe("Course(s) was deleted but the instructor still exists: " + Const.EOL + courseIdList.toString());
        }
        
        for (CourseAttributes ca : courseList) {
            courseSummaryList.put(ca.id, getCourseSummaryWithoutStats(ca));
        }
        
        return courseSummaryList;
    }
    
    /**
     * Returns a CSV for the details(name, email, status) of all students mapped to a given course.
     * @param courseId
     * @param instructorGoogleId
     * @return a CSV String with student details
     * @throws EntityDoesNotExistException
     */
    public String getCourseStudentListAsCsv(String courseId, String instructorGoogleId) throws EntityDoesNotExistException {

        HashMap<String, CourseDetailsBundle> courses = getCourseSummariesForInstructor(instructorGoogleId, false);
        CourseDetailsBundle course = courses.get(courseId);
        boolean hasSection = hasIndicatedSections(courseId);
        
        String export = "";
        export += "Course ID" + "," + Sanitizer.sanitizeForCsv(courseId) + Const.EOL + "Course Name," 
                  + Sanitizer.sanitizeForCsv(course.course.name) + Const.EOL + Const.EOL + Const.EOL;
        if (hasSection) {
            export += "Section" + ",";
        }
        export += "Team,Full Name,Last Name,Status,Email" + Const.EOL;
        
        for (SectionDetailsBundle section : course.sections) {
            for (TeamDetailsBundle team : section.teams) {
                for (StudentAttributes student : team.students) {
                    String studentStatus = null;
                    if (student.googleId == null || student.googleId.equals("")) {
                        studentStatus = Const.STUDENT_COURSE_STATUS_YET_TO_JOIN;
                    } else {
                        studentStatus = Const.STUDENT_COURSE_STATUS_JOINED;
                    }
                    
                    if (hasSection) {
                        export += Sanitizer.sanitizeForCsv(section.name) + ",";
                    }

                    export += Sanitizer.sanitizeForCsv(team.name) + "," 
                              + Sanitizer.sanitizeForCsv(StringHelper.removeExtraSpace(student.name)) + "," 
                              + Sanitizer.sanitizeForCsv(StringHelper.removeExtraSpace(student.lastName)) + "," 
                              + Sanitizer.sanitizeForCsv(studentStatus) + "," 
                              + Sanitizer.sanitizeForCsv(student.email) + Const.EOL;
                }
            }
        }
        return export;
    }

    public boolean hasIndicatedSections(String courseId) throws EntityDoesNotExistException {
        verifyCourseIsPresent(courseId);
        
        List<StudentAttributes> studentList = studentsLogic.getStudentsForCourse(courseId);
        for (StudentAttributes student : studentList) {
            if (!student.section.equals(Const.DEFAULT_SECTION)) {
                return true;
            }
        }
        return false;
    }
    
    
    public boolean isCourseArchived(String courseId, String instructorGoogleId) {
        CourseAttributes course = getCourse(courseId);
        InstructorAttributes instructor = instructorsLogic.getInstructorForGoogleId(courseId, instructorGoogleId);
        return isCourseArchived(course, instructor);
    }
    
    public boolean isCourseArchived(CourseAttributes course, InstructorAttributes instructor) {
        return (instructor.isArchived != null) ? instructor.isArchived : course.isArchived;
    }
    
    /**
     * Maps sections to relevant course id. 
     * @param courses
     * @return a hash map containing a list of sections as the value and relevant courseId as the key. 
     * @throws EntityDoesNotExistException
     */
    public Map<String, List<String>> getCourseIdToSectionNamesMap(List<CourseAttributes> courses)
                                    throws EntityDoesNotExistException {
        Map<String, List<String>> courseIdToSectionName = new HashMap<String, List<String>>();
        for (CourseAttributes course : courses) {
            List<String> sections = getSectionsNameForCourse(course);
            courseIdToSectionName.put(course.id, sections);
        }
        
        return courseIdToSectionName;
    }
    
    // TODO: Optimize extractActiveCourses() and extractArchivedCourses() to reduce the number of repeated calls of
    // isCourseArchived(), which retrieves information from the database
    
    /**
     * Returns a list of {@link CourseDetailsBundle} for all active courses mapped to a particular instructor.  
     * @param courseBundles all courses
     * @param googleId instructorGoogleId
     * @return a list of {@link CourseDetailsBundle}
     */
    public List<CourseDetailsBundle> extractActiveCourses(List<CourseDetailsBundle> courseBundles, String googleId) {
        List<CourseDetailsBundle> result = new ArrayList<CourseDetailsBundle>();
        for (CourseDetailsBundle courseBundle : courseBundles) {
            if (!isCourseArchived(courseBundle.course.id, googleId)) {
                result.add(courseBundle);
            }
        }
        return result;
    }
    
    /**
     * Returns a list of {@link CourseDetailsBundle} for all archived courses mapped to a particular instructor.  
     * @param courseBundles all courses
     * @param googleId instructorGoogleId
     * @return a list of {@link CourseDetailsBundle}
     */
    public List<CourseDetailsBundle> extractArchivedCourses(List<CourseDetailsBundle> courseBundles, String googleId) {
        List<CourseDetailsBundle> result = new ArrayList<CourseDetailsBundle>();
        for (CourseDetailsBundle courseBundle : courseBundles) {
            if (isCourseArchived(courseBundle.course.id, googleId)) {
                result.add(courseBundle);
            }
        }
        return result;
    }
    
    /**
     * Returns a list of courseIds for all archived courses for all instructors.
     * @param allCourses
     * @param instructorsForCourses
     * @return a list of courseIds
     */
    public List<String> getArchivedCourseIds(List<CourseAttributes> allCourses, Map<String, InstructorAttributes> instructorsForCourses) {
        List<String> archivedCourseIds = new ArrayList<String>();
        for (CourseAttributes course : allCourses) {
            InstructorAttributes instructor = instructorsForCourses.get(course.id);
            if (isCourseArchived(course, instructor)) {
                archivedCourseIds.add(course.id);
            }
        }
        return archivedCourseIds;
    }
    
}
