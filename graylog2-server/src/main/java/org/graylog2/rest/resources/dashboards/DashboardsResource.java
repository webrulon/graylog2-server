/**
 * Copyright 2013 Lennart Koopmann <lennart@torch.sh>
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.graylog2.rest.resources.dashboards;

import com.beust.jcommander.internal.Lists;
import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.Maps;
import org.bson.types.ObjectId;
import org.graylog2.dashboards.Dashboard;
import org.graylog2.dashboards.widgets.DashboardWidget;
import org.graylog2.database.*;
import org.graylog2.database.NotFoundException;
import org.graylog2.indexer.searches.timeranges.InvalidRangeParametersException;
import org.graylog2.rest.documentation.annotations.*;
import org.graylog2.rest.resources.RestResource;
import org.graylog2.rest.resources.dashboards.requests.AddWidgetRequest;
import org.graylog2.rest.resources.dashboards.requests.CreateRequest;
import org.graylog2.system.activities.Activity;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * @author Lennart Koopmann <lennart@torch.sh>
 */
@Api(value = "Dashboards", description = "Manage dashboards")
@Path("/dashboards")
public class DashboardsResource extends RestResource {
    private static final Logger LOG = LoggerFactory.getLogger(DashboardsResource.class);

    @POST
    @Timed
    @ApiOperation(value = "Create a dashboard")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@ApiParam(title = "JSON body", required = true) String body) {
        CreateRequest cr;
        try {
            cr = objectMapper.readValue(body, CreateRequest.class);
        } catch(IOException e) {
            LOG.error("Error while parsing JSON", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        // Create dashboard.
        Map<String, Object> dashboardData = Maps.newHashMap();
        dashboardData.put("title", cr.title);
        dashboardData.put("description", cr.description);
        dashboardData.put("creator_user_id", cr.creatorUserId);
        dashboardData.put("created_at", new DateTime(DateTimeZone.UTC));

        Dashboard dashboard = new Dashboard(dashboardData, core);
        ObjectId id;
        try {
            id = dashboard.save();
        } catch (ValidationException e) {
            LOG.error("Validation error.", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("dashboard_id", id.toStringMongod());

        return Response.status(Response.Status.CREATED).entity(json(result)).build();
    }

    @GET @Timed
    @ApiOperation(value = "Get a list of all dashboards")
    @Produces(MediaType.APPLICATION_JSON)
    public String list() {
        List<Map<String, Object>> dashboards = Lists.newArrayList();

        for (Dashboard dashboard: Dashboard.all(core)) {
            dashboards.add(dashboard.asMap());
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("total", dashboards.size());
        result.put("dashboards", dashboards);

        return json(result);
    }

    @GET @Timed
    @ApiOperation(value = "Get a single dashboards")
    @Path("/{dashboardId}")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Dashboard not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    @Produces(MediaType.APPLICATION_JSON)
    public String get(@ApiParam(title = "dashboardId", required = true) @PathParam("dashboardId") String dashboardId) {
        try {
            Dashboard dashboard = Dashboard.load(loadObjectId(dashboardId), core);
            return json(dashboard.asMap());
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }
    }

    @DELETE @Timed
    @ApiOperation(value = "Delete a dashboard and all its widgets")
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{dashboardId}")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Dashboard not found."),
            @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public Response delete(@ApiParam(title = "Dashboard ID", required = true) @PathParam("dashboardId") String dashboardId) {
        try {
            Dashboard dashboard = Dashboard.load(loadObjectId(dashboardId), core);
            dashboard.destroy();

            String msg = "Deleted dashboard <" + dashboard.getId() + ">. Reason: REST request.";
            LOG.info(msg);
            core.getActivityWriter().write(new Activity(msg, DashboardsResource.class));
        } catch (org.graylog2.database.NotFoundException e) {
            throw new WebApplicationException(404);
        }

        return Response.status(Response.Status.fromStatusCode(204)).build();
    }

    @POST
    @Timed
    @ApiOperation(value = "Add a widget to a dashboard")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Dashboard not found."),
            @ApiResponse(code = 400, message = "Validation error."),
            @ApiResponse(code = 400, message = "No such widget type.")
    })
    @Path("/{dashboardId}/widgets")
    public Response addWidget(@ApiParam(title = "JSON body", required = true) String body,
                              @ApiParam(title = "Dashboard ID", required = true) @PathParam("dashboardId") String dashboardId) {
        AddWidgetRequest awr;
        try {
            awr = objectMapper.readValue(body, AddWidgetRequest.class);
        } catch(IOException e) {
            LOG.error("Error while parsing JSON", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        DashboardWidget widget;
        try {
            widget = DashboardWidget.fromRequest(awr);
            Dashboard dashboard = Dashboard.load(new ObjectId(dashboardId), core);

            dashboard.addWidget(widget);
        } catch(NotFoundException e) {
            LOG.error("Dashboard not found.", e);
            throw new WebApplicationException(e, Response.Status.NOT_FOUND);
        } catch (ValidationException e1) {
            LOG.error("Validation error.", e1);
            throw new WebApplicationException(e1, Response.Status.BAD_REQUEST);
        } catch (DashboardWidget.NoSuchWidgetTypeException e2) {
            LOG.error("No such widget type.", e2);
            throw new WebApplicationException(e2, Response.Status.BAD_REQUEST);
        } catch (InvalidRangeParametersException e3) {
            LOG.error("Invalid timerange parameters provided.", e3);
            throw new WebApplicationException(e3, Response.Status.BAD_REQUEST);
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("widget_id", widget.getId());

        return Response.status(Response.Status.CREATED).entity(json(result)).build();
    }

    @DELETE @Timed
    @ApiOperation(value = "Delete a widget")
    @Path("/{dashboardId}/widgets/{widgetId}")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Dashboard not found."),
            @ApiResponse(code = 404, message = "Widget not found.")
    })
    @Produces(MediaType.APPLICATION_JSON)
    public Response remove(
            @ApiParam(title = "Dashboard ID", required = true) @PathParam("dashboardId") String dashboardId,
            @ApiParam(title = "Widget ID", required = true) @PathParam("widgetId") String widgetId) {
        if (dashboardId == null || dashboardId.isEmpty()) {
            LOG.error("Missing dashboard ID. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }

        if (widgetId == null || widgetId.isEmpty()) {
            LOG.error("Missing widget ID. Returning HTTP 400.");
            throw new WebApplicationException(400);
        }

        try {
            Dashboard dashboard = Dashboard.load(new ObjectId(dashboardId), core);
            dashboard.removeWidget(widgetId);
        } catch(NotFoundException e) {
            LOG.error("Dashboard not found.", e);
            throw new WebApplicationException(e, Response.Status.NOT_FOUND);
        }

        String msg = "Deleted widget <" + widgetId + "> from dashboard <" + dashboardId + ">. Reason: REST request.";
        LOG.info(msg);
        core.getActivityWriter().write(new Activity(msg, DashboardsResource.class));

        return Response.status(Response.Status.NO_CONTENT).build();
    }

    /*
     * TODO:
     *  - add query & timerange info to count widget
     *  - build registry
     *  - build updater (just printing out registered widgets and settings for now)
     *  - read from persisted
     *  - return results via REST
     */

}