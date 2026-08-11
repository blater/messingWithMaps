package dn;

import dn.strategicmap.render.LabelCandidate;
import dn.strategicmap.render.RegionStyleSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generic map-facing output of the top-level politics/map composition. */
public record PoliticalMapPresentation(
    RegionStyleSnapshot regionStyles,
    List<LabelCandidate> groupLabels,
    Set<String> capitalPlaceIds,
    Map<String, String> tooltipAdditions) {}
