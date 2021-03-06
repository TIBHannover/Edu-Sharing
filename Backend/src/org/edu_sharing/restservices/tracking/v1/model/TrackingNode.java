package org.edu_sharing.restservices.tracking.v1.model;

import org.edu_sharing.restservices.shared.Node;
import org.edu_sharing.service.tracking.TrackingService;
import org.edu_sharing.service.tracking.model.StatisticEntry;
import org.edu_sharing.service.tracking.model.StatisticEntryNode;

import java.io.Serializable;
import java.util.Map;

public class TrackingNode extends Tracking {
    private final Node node;
    public TrackingNode(Node node, Authority authority, String date, Map<TrackingService.EventType,Integer> counts, Map<String,Serializable> fields, Map<TrackingService.EventType, Map<String, Map<String, Long>>> groups){
        super(date,authority,counts,fields,groups);
        this.node=node;
    }

    public Node getNode() {
        return node;
    }
}
