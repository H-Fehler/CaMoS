package camos.mode_execution.mobilitymodels.modehelpers;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.constraint.ConstraintManager;
import com.graphhopper.jsprit.core.problem.constraint.HardActivityConstraint;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.util.shapes.GHPoint;
import camos.GeneralManager;
import camos.mode_execution.*;
import camos.mode_execution.groupings.Ride;
import camos.mode_execution.groupings.Stop;
import camos.mode_execution.groupings.Stopreason;
import camos.mode_execution.mobilitymodels.MobilityMode;
import camos.mode_execution.mobilitymodels.MobilityType;
import camos.mode_execution.mobilitymodels.tsphelpers.ActivityOrderConstraint;
import camos.mode_execution.mobilitymodels.tsphelpers.ActivityWaitConstraintNoneAllowed;
import camos.mode_execution.mobilitymodels.tsphelpers.ActivityWaitConstraintOneAllowed;
import camos.mode_execution.mobilitymodels.tsphelpers.TransportCosts;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * A class for grouping helpful functions for the mobility modes.
 */
public class CommonFunctionHelper {

    /**
     * Get the best graphhopper path for a start and end
     *
     * @param  graphHopper     the GraphHopper instance for calculating the best path
     * @param  start           the ride to calculate the best path for
     * @param  end             the ride to calculate the best path for
     * @return                 the best path
     */
    public static ResponsePath getSimpleBestGraphhopperPath(GraphHopper graphHopper, Coordinate start, Coordinate end){
        GHPoint ghPointStart = new GHPoint(start.getLatitude(),start.getLongitude());
        GHPoint ghPointEnd = new GHPoint(end.getLatitude(),end.getLongitude());
        GHRequest ghRequest = new GHRequest(ghPointStart,ghPointEnd).setProfile("car").setLocale(Locale.GERMANY);

        return graphHopper.route(ghRequest).getBest();
    }


    /**
     * Calculate and return the ride end time.
     *
     * @param  ride the ride for which the end time is to be calculated
     * @return      the ride end time
     */
    public static LocalDateTime getRideEndTime(GraphHopper graphHopper, Ride ride){
        ResponsePath path = CommonFunctionHelper.getSimpleBestGraphhopperPath(graphHopper,ride.getStartPosition(),ride.getEndPosition());
        if(path==null){
            throw new RuntimeException("No path found with graphhopper!");
        }
        long timeInMinutes = path.getTime()/60000L;
        return ride.getStartTime().plusMinutes(timeInMinutes);
    }


    /**
     * Calculate the time an agent needs to start driving to arrive at their ideal arrival time
     *
     * @param  request the request from which the ideal arrival time of an agent is extracted
     * @return         the time the agent needs to start driving
     */
    public static LocalDateTime calculateToUniDriveStartTime(Request request){
        GHPoint ghPointStart = new GHPoint(request.getHomePosition().getLatitude(),request.getHomePosition().getLongitude());
        Coordinate uniCoordinate = request.getDropOffPosition();
        GHPoint ghPointEnd = new GHPoint(uniCoordinate.getLatitude(), uniCoordinate.getLongitude());
        GHRequest ghRequest = new GHRequest(ghPointStart,ghPointEnd).setProfile("car").setLocale(Locale.GERMANY);
        ResponsePath path = ModeExecutionManager.graphHopper.route(ghRequest).getBest();

        long timeInMinutes = path.getTime()/60000L;
        return request.getFavoredArrivalTime().minusMinutes(timeInMinutes);
    }


    /**
     * Try to find another routing solution without waiting times at the stops //TODO besser säubern
     * @param  mobilityType current mobility mode which the solution is for
     * @param  requesttype  direction the ride is going in
     * @param  solution     the old solution that is to be corrected
     * @param  vrpBuilder   the builder for building the routing problem
     * @return              the corrected solution without waiting times or null if none exists
     */
    public static VehicleRoutingProblemSolution correctSolution(MobilityType mobilityType, Requesttype requesttype, VehicleRoutingProblemSolution solution, VehicleRoutingProblem.Builder vrpBuilder) {
        List<TourActivity> activities = ((List<VehicleRoute>)solution.getRoutes()).get(0).getActivities();
        VehicleRoutingProblem problem = vrpBuilder.build();
        Vehicle vehicle = vrpBuilder.getAddedVehicles().iterator().next();

        TourActivity firstActivity = activities.get(0);
        int index = firstActivity.getIndex();
        Job firstJob = null;
        for (Job job : problem.getJobsWithLocation()) {
            if (job.getIndex() == index) {
                firstJob = job;
                break;
            }
        }
        if(firstJob == null){
            throw new RuntimeException("No first job found for 'correctSolution()'!");
        }

        Collection<Job> jobs = new HashSet<>(vrpBuilder.getAddedJobs());
        jobs.remove(firstJob);

        vrpBuilder = VehicleRoutingProblem.Builder.newInstance();
        vrpBuilder.addAllJobs(jobs);
        double referenceTime = mobilityType == MobilityType.RIDEPOOLING ? activities.get(activities.size() - 1).getEndTime() : ((List<VehicleRoute>) solution.getRoutes()).get(0).getEnd().getArrTime();
        referenceTime = (referenceTime - solution.getCost()) + firstActivity.getArrTime();
        if (referenceTime < ((Service) firstJob).getTimeWindow().getStart() || referenceTime > ((Service) firstJob).getTimeWindow().getEnd()) {
            return null;
        }
        vrpBuilder.addJob(Service.Builder.newInstance(firstJob.getId()).setLocation(firstActivity.getLocation()).setServiceTime(firstActivity.getOperationTime()).addTimeWindow(referenceTime, referenceTime).setPriority(1).build());
        vrpBuilder.setRoutingCost(new TransportCosts());
        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        vrpBuilder.addVehicle(vehicle);

        problem = vrpBuilder.build();
        StateManager stateManager = new StateManager(problem);
        ConstraintManager constraintManager = new ConstraintManager(problem, stateManager);
        constraintManager.addConstraint(new ActivityOrderConstraint(), ConstraintManager.Priority.CRITICAL);
        HardActivityConstraint constraint = mobilityType == MobilityType.RIDESHARING && requesttype == Requesttype.DRIVEHOME ? new ActivityWaitConstraintOneAllowed() : new ActivityWaitConstraintNoneAllowed();
        constraintManager.addConstraint(constraint, ConstraintManager.Priority.CRITICAL);
        VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem).setStateAndConstraintManager(stateManager, constraintManager).buildAlgorithm();
        Jsprit.createAlgorithm(problem);
        algorithm.setMaxIterations(50);
        if (mobilityType == MobilityType.RIDESHARING && requesttype == Requesttype.DRIVEHOME) {
            int counter = 0;
            List<TourActivity> activities2 = ((List<VehicleRoute>) Solutions.bestOf(algorithm.searchSolutions()).getRoutes()).get(0).getActivities();
            int activityCounter = 0;
            if (Solutions.bestOf(algorithm.searchSolutions()).getUnassignedJobs().isEmpty()) {
                for (TourActivity a : activities2) {
                    long timeForStop = activityCounter == 0 ? 0 : GeneralManager.stopTime;
                    if (activityCounter > 0 && a.getEndTime() - a.getArrTime() > timeForStop) {
                        counter++;
                    }
                    activityCounter++;
                }
            }
            if (counter > 0) {
                return null;
            }
        }
        return Solutions.bestOf(algorithm.searchSolutions());
    }


    /**
     * Add a ride to the timeToRideStart map
     *
     * @param  time the time for the ride start
     * @param  ride the ride to add
     */
    public static void addToRideStartList(LocalDateTime time, Ride ride, Map<LocalDateTime,List<Ride>> timeToRideStart){
        List<Ride> rideList;
        if(timeToRideStart.containsKey(time)){
            rideList = timeToRideStart.get(time);
            rideList.add(ride);
        }else{
            rideList = new ArrayList<>();
            rideList.add(ride);
        }
        timeToRideStart.put(time,rideList);
    }

    public static void updateSortedTimes(List<LocalDateTime> sortedTimes, LocalDateTime time, boolean remove){
        if(remove){
            sortedTimes.remove(time);
        }else{
            sortedTimes.add(time);
        }
        Collections.sort(sortedTimes);
    }


    public static void filterWilling(double percentOfWillingStudents, List<Agent> willingAgents, List<Agent> unWillingAgents){
        if (percentOfWillingStudents < 100.0) {
            willingAgents.removeIf(a -> !a.isWillingToUseAlternatives());
            unWillingAgents.removeIf(Agent::isWillingToUseAlternatives);
        }
    }

    public static void calculateAcceptedDrivingTimes(List<Agent> agents, MobilityMode compareMode, String acceptedDrivingTime) {
        if (compareMode.getMinutesTravelled() == null || !compareMode.getMinutesTravelled().isEmpty()) {
            if(acceptedDrivingTime.contains("log")){
                if (acceptedDrivingTime.contains("+")) {
                    for (Agent agent : agents) {
                        double oneWayMinutesTravelled = compareMode.getMinutesTravelled().get(agent) / 2;
                        agent.setWillingToRideInMinutes((long) Math.max(2, oneWayMinutesTravelled + customLog(Double.parseDouble(StringUtils.substringBetween(GeneralManager.acceptedDrivingTime, "log", "(")), oneWayMinutesTravelled)));
                    }
                }
            }
        } else {
            throw new RuntimeException("At first, the compare mode simulation has to run.");
        }
    }



    public static void calculateSecondsBetweenDropOffs(Map<String, Long> secondsBetweenDropOffs, Map<String, Coordinate> postcodeToCoordinate) {
        for (String postcode : postcodeToCoordinate.keySet()) {
            for (String postcode2 : postcodeToCoordinate.keySet()) {
                if (!postcode.equals(postcode2) && !secondsBetweenDropOffs.containsKey(postcode + "-" + postcode2)) {
                    GHPoint ghPointStart = new GHPoint(postcodeToCoordinate.get(postcode).getLatitude(), ModeExecutionManager.postcodeToCoordinate.get(postcode).getLongitude());
                    GHPoint ghPointEnd = new GHPoint(postcodeToCoordinate.get(postcode2).getLatitude(), ModeExecutionManager.postcodeToCoordinate.get(postcode2).getLongitude());
                    GHRequest ghRequest = new GHRequest(ghPointStart, ghPointEnd).setProfile("car").setLocale(Locale.GERMANY);
                    GHResponse rsp = ModeExecutionManager.graphHopper.route(ghRequest);
                    ResponsePath path = rsp.getBest();
                    secondsBetweenDropOffs.put(postcode + "-" + postcode2, path.getTime() / 1000);
                }
            }
        }
    }


    public static boolean isOverlapping(LocalDateTime start1, LocalDateTime end1, LocalDateTime start2, LocalDateTime end2) {
        return !start1.isAfter(end2) && !start2.isAfter(end1);
    }


    public static int getPopularElement(int[] a){
        int count = 1, tempCount;
        int popular = a[0];
        int temp;
        for (int i = 0; i < (a.length - 1); i++){
            temp = a[i];
            tempCount = 0;
            for (int j = 1; j < a.length; j++){
                if (temp == a[j])
                    tempCount++;
            }
            if (tempCount > count){
                popular = temp;
                count = tempCount;
            }
        }
        return popular;
    }


    public static String getIntervalString(LocalDateTime start, LocalDateTime end) {
        return start.format(GeneralManager.dateTimeFormatter) + "-" + end.format(GeneralManager.dateTimeFormatter);
    }



    public static double customLog(double base, double logNumber) {
        return Math.log(logNumber) / Math.log(base);
    }


    public static List<Stop> getAgentStops(Ride ride, Agent agent){
        Coordinate pickupLocation = ride.getTypeOfGrouping()==Requesttype.DRIVETOUNI ? agent.getHomePosition() : agent.getRequest().getDropOffPosition();
        Coordinate dropoffLocation = ride.getTypeOfGrouping()==Requesttype.DRIVETOUNI ? agent.getRequest().getDropOffPosition() : agent.getHomePosition();
        List<Stop> stops = new ArrayList<>();
        for(Stop stop : ride.getStops()){
            if(stop.getReasonForStopping()== Stopreason.PICKUP && stop.getStopCoordinate().equals(pickupLocation)){
                stops.add(stop);
            }else if(stop.getReasonForStopping()==Stopreason.DROPOFF && stop.getStopCoordinate().equals(dropoffLocation)){
                stops.add(stop);
            }
        }
        if(stops.isEmpty()){
            if(ride.getTypeOfGrouping()==Requesttype.DRIVETOUNI){
                stops.add(new Stop(ride.getEndTime(),ride.getEndTime(),ride.getEndPosition(),Stopreason.DROPOFF,null));
            }else{
                stops.add(new Stop(ride.getStartTime(),ride.getStartTime(),ride.getStartPosition(),Stopreason.PICKUP,null));
            }
        }
        return stops;
    }

}


