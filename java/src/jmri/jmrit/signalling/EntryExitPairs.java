package jmri.jmrit.signalling;

import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.annotation.CheckForNull;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import jmri.ConfigureManager;
import jmri.InstanceManager;
import jmri.JmriException;
import jmri.Manager;
import jmri.NamedBean;
import jmri.Sensor;
import jmri.jmrit.display.layoutEditor.LayoutBlock;
import jmri.jmrit.display.layoutEditor.LayoutBlockConnectivityTools;
import jmri.jmrit.display.layoutEditor.LayoutEditor;
import jmri.jmrit.signalling.entryexit.DestinationPoints;
import jmri.jmrit.signalling.entryexit.PointDetails;
import jmri.jmrit.signalling.entryexit.Source;
import jmri.jmrit.signalling.entryexit.StackNXPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements an Entry Exit based method of setting turnouts, setting up signal
 * logic and allocating blocks through a path based on the Layout Editor.
 * <p>
 * The route is based upon having a sensor assigned at a known location on the
 * panel (set at the boundary of two different blocks) through to a sensor at a
 * remote location on the same panel. Using the layout block routing, a path can
 * then be set between the two sensors so long as one exists and no
 * section of track is set occupied. If available an alternative route will be
 * used when the direct path is occupied (blocked).
 * <p>
 * Initial implementation only handles the setting up of turnouts on a path.
 *
 * @author Kevin Dickerson Copyright (C) 2011
 */
public class EntryExitPairs implements jmri.Manager, jmri.InstanceManagerAutoDefault {

    public int routingMethod = LayoutBlockConnectivityTools.METRIC;

    final static int HOPCOUNT = LayoutBlockConnectivityTools.HOPCOUNT;
    final static int METRIC = LayoutBlockConnectivityTools.METRIC;

    public final static int NXBUTTONSELECTED = 0x08;
    public final static int NXBUTTONACTIVE = Sensor.ACTIVE;
    public final static int NXBUTTONINACTIVE = Sensor.INACTIVE;

    private int settingTimer = 2000;

    public int getSettingTimer() {
        return settingTimer;
    }

    public void setSettingTimer(int i) {
        settingTimer = i;
    }

    private Color settingRouteColor = null;

    public boolean useDifferentColorWhenSetting() {
        return (settingRouteColor == null ? false : true);
    }

    public Color getSettingRouteColor() {
        return settingRouteColor;
    }

    public void setSettingRouteColor(Color col) {
        settingRouteColor = col;
    }

    /**
     * Constant value to represent that the entryExit will only set up the
     * turnouts between two different points.
     */
    public final static int SETUPTURNOUTSONLY = 0x00;

    /**
     * Constant value to represent that the entryExit will set up the turnouts
     * between two different points and configure the Signal Mast Logic to use
     * the correct blocks.
     */
    public final static int SETUPSIGNALMASTLOGIC = 0x01;

    /**
     * Constant value to represent that the entryExit will do full interlocking.
     * It will set the turnouts and "reserve" the blocks.
     */
    public final static int FULLINTERLOCK = 0x02;

    boolean allocateToDispatcher = false;

    public final static int PROMPTUSER = 0x00;
    public final static int AUTOCLEAR = 0x01;
    public final static int AUTOCANCEL = 0x02;

    int routeClearOption = PROMPTUSER;

    static JPanel glassPane = new JPanel();

    /**
     * Delay between issuing Turnout commands
     */
    public int turnoutSetDelay = 0;

    /**
     * Constructor for creating an EntryExitPairs object and create a transparent JPanel for it.
     */
    public EntryExitPairs() {
        if (InstanceManager.getNullableDefault(ConfigureManager.class) != null) {
            InstanceManager.getDefault(ConfigureManager.class).registerUser(this);
        }
        InstanceManager.getDefault(jmri.jmrit.display.layoutEditor.LayoutBlockManager.class).addPropertyChangeListener(propertyBlockManagerListener);

        glassPane.setOpaque(false);
        glassPane.setLayout(null);
        glassPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                e.consume();
            }
        });
    }

    public void setDispatcherIntegration(boolean boo) {
        allocateToDispatcher = boo;
    }

    public boolean getDispatcherIntegration() {
        return allocateToDispatcher;
    }

    /**
     * Get the transparent JPanel for this EntryExitPairs.
     * @return JPanel overlay
     */
    public JPanel getGlassPane() {
        return glassPane;
    }

    HashMap<PointDetails, Source> nxpair = new HashMap<PointDetails, Source>();

    public void addNXSourcePoint(LayoutBlock facing, List<LayoutBlock> protecting, NamedBean loc, LayoutEditor panel) {
        PointDetails point = providePoint(facing, protecting, panel);
        point.setRefObject(loc);
    }

    public void addNXSourcePoint(NamedBean source) {
        PointDetails point = null;
        ArrayList<LayoutEditor> layout = jmri.jmrit.display.PanelMenu.instance().getLayoutEditorPanelList();
        for (int i = 0; i < layout.size(); i++) {
            point = providePoint(source, layout.get(i));
        }
        if (point == null) {
            log.error("Unable to find a location on any panel for item " + source.getDisplayName());
            return;
        }
    }

    public void addNXSourcePoint(NamedBean source, LayoutEditor panel) {
        if (source == null) {
            log.error("source bean supplied is null");
            return;
        }
        if (panel == null) {
            log.error("panel supplied is null");
            return;
        }
        PointDetails point;
        point = providePoint(source, panel);
        if (point == null) {
            log.error("Unable to find a location on the panel " + panel.getLayoutName() + " for item " + source.getDisplayName());
            return;
        }
    }

    public Object getEndPointLocation(NamedBean source, LayoutEditor panel) {
        if (source == null) {
            log.error("Source bean past is null");
            return null;
        }
        if (panel == null) {
            log.error("panel past is null");
            return null;
        }
        PointDetails sourcePoint = getPointDetails(source, panel);
        if (sourcePoint == null) {
            log.error("Point is not located");
            return null;
        }
        return sourcePoint.getRefLocation();
    }

    @Override
    public int getXMLOrder() {
        return ENTRYEXIT;
    }

    public NamedBean getBySystemName(String systemName) {
        for (Source e : nxpair.values()) {
            DestinationPoints pd = e.getByUniqueId(systemName);
            if (pd != null) {
                return pd;
            }
        }
        return null;
    }

    @Override
    public NamedBean getBeanBySystemName(String systemName) {
        return getBySystemName(systemName);
    }

    @Override
    public NamedBean getBeanByUserName(String userName) {
        for (Source e : nxpair.values()) {
            DestinationPoints pd = e.getByUserName(userName);
            if (pd != null) {
                return pd;
            }
        }
        return null;
    }

    @Override
    public NamedBean getNamedBean(String name) {
        NamedBean b = getBeanByUserName(name);
        if (b != null) {
            return b;
        }
        return getBeanBySystemName(name);
    }

    @Override
    public String getSystemPrefix() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public char typeLetter() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String makeSystemName(String s) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Enforces, and as a user convenience converts to, the standard form for a system name
     * for the NamedBeans handled by this manager.
     *
     * @param inputName System name to be normalized
     * @throws NamedBean.BadSystemNameException If the inputName can't be converted to normalized form
     * @return A system name in standard normalized form 
     */
    @Override
    @CheckReturnValue
    public @Nonnull String normalizeSystemName(@Nonnull String inputName) throws NamedBean.BadSystemNameException {
        return inputName;
    }

    @Override
    public String[] getSystemNameArray() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public List<String> getSystemNameList() {
        return getEntryExitList();
    }

    @Override
    public List<NamedBean> getNamedBeanList() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void register(NamedBean n) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void deregister(NamedBean n) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void setClearDownOption(int i) {
        routeClearOption = i;
    }

    public int getClearDownOption() {
        return routeClearOption;
    }

    @Override
    public void dispose() {
    }

    /**
     * Generate the point details, given a known source and a
     * Layout Editor panel.
     *
     * @param source Origin of movement
     * @param panel  A Layout Editor panel
     * @return A PointDetails object
     */
    public PointDetails providePoint(NamedBean source, LayoutEditor panel) {
        PointDetails sourcePoint = getPointDetails(source, panel);
        if (sourcePoint == null) {
            LayoutBlock facing = InstanceManager.getDefault(jmri.jmrit.display.layoutEditor.LayoutBlockManager.class).getFacingBlockByNamedBean(source, panel);
            List<LayoutBlock> protecting = InstanceManager.getDefault(jmri.jmrit.display.layoutEditor.LayoutBlockManager.class).getProtectingBlocksByNamedBean(source, panel);
            if ((facing == null) && (protecting == null)) {
                log.error("Unable to find facing and protecting block");
                return null;
            }
            sourcePoint = providePoint(facing, protecting, panel);
            sourcePoint.setRefObject(source);
        }
        return sourcePoint;
    }

    /**
     * Return a list of all source (origin) points on a given
     * Layout Editor panel.
     *
     * @param panel  A Layout Editor panel
     * @return A list of source objects
     */
    public List<Object> getSourceList(LayoutEditor panel) {
        List<Object> list = new ArrayList<Object>();

        for (Entry<PointDetails, Source> e : nxpair.entrySet()) {
            Object obj = (e.getKey()).getRefObject();
            LayoutEditor pan = (e.getKey()).getPanel();
            if (pan == panel) {
                if (!list.contains(obj)) {
                    list.add(obj);
                }
            } // end while
        }
        return list;
    }

    public Source getSourceForPoint(PointDetails pd) {
        return nxpair.get(pd);
    }

    public int getNxPairNumbers(LayoutEditor panel) {
        int total = 0;
        for (Entry<PointDetails, Source> e : nxpair.entrySet()) {
            PointDetails key = e.getKey();
            LayoutEditor pan = key.getPanel();
            if (pan == panel) {
                total = total + e.getValue().getNumberOfDestinations();
            } // end while
        }

        return total;
    }

    public void setMultiPointRoute(PointDetails requestpd, LayoutEditor panel) {
        for (PointDetails pd : pointDetails) {
            if (pd != requestpd && pd.getPanel() == panel) {
                if (pd.getNXState() == NXBUTTONSELECTED) {
                    setMultiPointRoute(pd, requestpd);
                    return;
                }
            }
        }
    }

    private void setMultiPointRoute(PointDetails fromPd, PointDetails toPd) {
        boolean cleardown = false;
        if (fromPd.isRouteFromPointSet() && toPd.isRouteToPointSet()) {
            cleardown = true;
        }
        for (LayoutBlock pro : fromPd.getProtecting()) {
            try {
                jmri.jmrit.display.layoutEditor.LayoutBlockManager lbm = InstanceManager.getDefault(jmri.jmrit.display.layoutEditor.LayoutBlockManager.class);
                LayoutBlock toProt = null;
                if (!toPd.getProtecting().isEmpty()) {
                    toProt = toPd.getProtecting().get(0);
                }
                boolean result = lbm.getLayoutBlockConnectivityTools().checkValidDest(fromPd.getFacing(), pro, toPd.getFacing(), toProt, LayoutBlockConnectivityTools.SENSORTOSENSOR);
                if (result) {
                    ArrayList<LayoutBlock> blkList = lbm.getLayoutBlockConnectivityTools().getLayoutBlocks(fromPd.getFacing(), toPd.getFacing(), pro, cleardown, LayoutBlockConnectivityTools.NONE);
                    if (!blkList.isEmpty()) {
                        List<jmri.NamedBean> beanList = lbm.getLayoutBlockConnectivityTools().getBeansInPath(blkList, fromPd.getPanel(), jmri.Sensor.class);
                        PointDetails fromPoint = fromPd;
                        refCounter++;
                        if (!beanList.isEmpty()) {
                            for (int i = 1; i < beanList.size(); i++) {
                                NamedBean nb = beanList.get(i);
                                PointDetails cur = getPointDetails(nb, fromPd.getPanel());
                                Source s = nxpair.get(fromPoint);
                                if (s != null) {
                                    routesToSet.add(new SourceToDest(s, s.getDestForPoint(cur), false, refCounter));
                                }
                                fromPoint = cur;
                            }
                        }
                        Source s = nxpair.get(fromPoint);
                        if (s != null) {
                            routesToSet.add(new SourceToDest(s, s.getDestForPoint(toPd), false, refCounter));
                        }
                        processRoutesToSet();
                        return;
                    }
                }
            } catch (jmri.JmriException e) {
                //Can be considered normal if route is blocked
            }
        }
        fromPd.setNXButtonState(NXBUTTONINACTIVE);
        toPd.setNXButtonState(NXBUTTONINACTIVE);
    }

    int refCounter = 0;

    /**
     * List holding SourceToDest sets of routes between two points.
     */
    ArrayList<SourceToDest> routesToSet = new ArrayList<SourceToDest>();

    /**
     * Class to store NX sets consisting of a source point, a destination point,
     * a direction and a reference.
     */
    static class SourceToDest {

        Source s = null;
        DestinationPoints dp = null;
        boolean direction = false;
        int ref = -1;

        /**
         * Constructor for a SourceToDest element.
         * @param s a source point
         * @param dp a destination point
         * @param dir a direction
         * @param ref Integer used as reference
         */
        SourceToDest(Source s, DestinationPoints dp, boolean dir, int ref) {
            this.s = s;
            this.dp = dp;
            this.direction = dir;
            this.ref = ref;
        }
    }

    int currentDealing = 0;

    /**
     * Activate each SourceToDest set in routesToSet
     */
    synchronized void processRoutesToSet() {
        if (routesToSet.isEmpty()) {
            return;
        }
        Source s = routesToSet.get(0).s;
        DestinationPoints dp = routesToSet.get(0).dp;
        boolean dir = routesToSet.get(0).direction;
        currentDealing = routesToSet.get(0).ref;
        routesToSet.remove(0);

        dp.addPropertyChangeListener(propertyDestinationListener);
        s.activeBean(dp, dir);
    }

    /**
     * Remove remaining SourceToDest sets in routesToSet
     */
    synchronized void removeRemainingRoute() {
        ArrayList<SourceToDest> toRemove = new ArrayList<SourceToDest>();
        for (SourceToDest rts : routesToSet) {
            if (rts.ref == currentDealing) {
                toRemove.add(rts);
                rts.dp.getDestPoint().setNXButtonState(NXBUTTONINACTIVE);
            }
        }
        for (SourceToDest rts : toRemove) {
            routesToSet.remove(rts);
        }
    }

    protected PropertyChangeListener propertyDestinationListener = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent e) {
            ((DestinationPoints) e.getSource()).removePropertyChangeListener(this);
            if (e.getPropertyName().equals("active")) {
                processRoutesToSet();
            } else if (e.getPropertyName().equals("stacked") || e.getPropertyName().equals("failed") || e.getPropertyName().equals("noChange")) {
                removeRemainingRoute();
            }
        }
    };

    ArrayList<Object> destinationList = new ArrayList<Object>();

    // Need to sort out the presentation of the name here rather than using the point ID.
    // This is used for the creation and display of information in the table.
    // The presentation of the name might have to be done at the table level.
    public ArrayList<Object> getNxSource(LayoutEditor panel) {
        ArrayList<Object> source = new ArrayList<Object>();
        destinationList = new ArrayList<Object>();

        for (Entry<PointDetails, Source> e : nxpair.entrySet()) {
            PointDetails key = e.getKey();
            LayoutEditor pan = key.getPanel();
            if (pan == panel) {
                List<PointDetails> dest = nxpair.get(key).getDestinationPoints();
                for (int i = 0; i < dest.size(); i++) {
                    destinationList.add(dest.get(i).getRefObject());
                    source.add(key.getRefObject());
                }
            }
        }
        return source;
    }

    public ArrayList<Object> getNxDestination() {
        return destinationList;
    }

    public ArrayList<LayoutEditor> getSourcePanelList() {
        ArrayList<LayoutEditor> list = new ArrayList<LayoutEditor>();

        for (Entry<PointDetails, Source> e : nxpair.entrySet()) {
            PointDetails key = e.getKey();
            LayoutEditor pan = key.getPanel();
            if (!list.contains(pan)) {
                list.add(pan);
            }
        }
        return list;
    }

    /**
     * Return a point if it already exists, or create a new one if not.
     */
    private PointDetails providePoint(LayoutBlock source, List<LayoutBlock> protecting, LayoutEditor panel) {
        PointDetails sourcePoint = getPointDetails(source, protecting, panel);
        if (sourcePoint == null) {
            sourcePoint = new PointDetails(source, protecting);
            sourcePoint.setPanel(panel);
        }
        return sourcePoint;
    }

    public void addNXDestination(NamedBean source, NamedBean destination, LayoutEditor panel) {
        addNXDestination(source, destination, panel, null);
    }

    public void addNXDestination(NamedBean source, NamedBean destination, LayoutEditor panel, String id) {
        if (source == null) {
            log.error("no source Object provided");
            return;
        }
        if (destination == null) {
            log.error("no destination Object provided");
            return;
        }
        PointDetails sourcePoint = providePoint(source, panel);
        if (sourcePoint == null) {
            log.error("source point for " + source.getDisplayName() + " not created addNXDes");
            return;
        }

        sourcePoint.setPanel(panel);
        sourcePoint.setRefObject(source);
        PointDetails destPoint = providePoint(destination, panel);
        if (destPoint != null) {
            destPoint.setPanel(panel);
            destPoint.setRefObject(destination);
            if (!nxpair.containsKey(sourcePoint)) {
                nxpair.put(sourcePoint, new Source(sourcePoint));
            }
            nxpair.get(sourcePoint).addDestination(destPoint, id);
        }

        firePropertyChange("length", null, null);
    }

    public ArrayList<Object> getDestinationList(Object obj, LayoutEditor panel) {
        ArrayList<Object> list = new ArrayList<Object>();
        if (nxpair.containsKey(getPointDetails(obj, panel))) {
            List<PointDetails> from = nxpair.get(getPointDetails(obj, panel)).getDestinationPoints();
            for (int i = 0; i < from.size(); i++) {
                list.add(from.get(i).getRefObject());
            }
        }
        return list;
    }

    public void deleteNxPair(NamedBean source, NamedBean destination, LayoutEditor panel) {
        PointDetails sourcePoint = getPointDetails(source, panel);
        if (sourcePoint == null) {
            if (log.isDebugEnabled()) {
                log.debug("source " + source.getDisplayName() + " does not exist so can not delete pair");
            }
            return;
        }

        PointDetails destPoint = getPointDetails(destination, panel);
        if (destPoint == null) {
            if (log.isDebugEnabled()) {
                log.debug("destination " + destination.getDisplayName() + " does not exist so can not delete pair");
            }
            return;
        }

        if (nxpair.containsKey(sourcePoint)) {
            nxpair.get(sourcePoint).removeDestination(destPoint);
            firePropertyChange("length", null, null);
            if (nxpair.get(sourcePoint).getDestinationPoints().size() == 0) {
                nxpair.remove(sourcePoint);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("source " + source.getDisplayName() + " is not a valid source so can not delete pair");
        }

    }

    public boolean isDestinationValid(Object source, Object dest, LayoutEditor panel) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            return nxpair.get(getPointDetails(source, panel)).isDestinationValid(getPointDetails(dest, panel));
        }
        return false;
    }

    public boolean isUniDirection(Object source, LayoutEditor panel, Object dest) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            return nxpair.get(getPointDetails(source, panel)).getUniDirection(dest, panel);
        }
        return false;
    }

    public void setUniDirection(Object source, LayoutEditor panel, Object dest, boolean set) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            nxpair.get(getPointDetails(source, panel)).setUniDirection(dest, panel, set);
        }
    }

    public boolean canBeBiDirectional(Object source, LayoutEditor panel, Object dest) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            return nxpair.get(getPointDetails(source, panel)).canBeBiDirection(dest, panel);
        }
        return false;
    }

    public boolean isEnabled(Object source, LayoutEditor panel, Object dest) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            return nxpair.get(getPointDetails(source, panel)).isEnabled(dest, panel);
        }
        return false;
    }

    public void setEnabled(Object source, LayoutEditor panel, Object dest, boolean set) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            nxpair.get(getPointDetails(source, panel)).setEnabled(dest, panel, set);
        }
    }

    public void setEntryExitType(Object source, LayoutEditor panel, Object dest, int set) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            nxpair.get(getPointDetails(source, panel)).setEntryExitType(dest, panel, set);
        }
    }

    public int getEntryExitType(Object source, LayoutEditor panel, Object dest) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            return nxpair.get(getPointDetails(source, panel)).getEntryExitType(dest, panel);
        }
        return 0x00;
    }

    public String getUniqueId(Object source, LayoutEditor panel, Object dest) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            return nxpair.get(getPointDetails(source, panel)).getUniqueId(dest, panel);
        }
        return null;
    }

    public List<String> getEntryExitList() {
        ArrayList<String> destlist = new ArrayList<String>();
        for (Source e : nxpair.values()) {
            destlist.addAll(e.getDestinationUniqueId());
        }
        return destlist;
    }

    // protecting helps us to determine which direction we are going.
    // validateOnly flag is used, if all we are doing is simply checking to see if the source/destpoints are valid
    // when creating the pairs in the user GUI
    public boolean isPathActive(Object sourceObj, Object destObj, LayoutEditor panel) {
        PointDetails pd = getPointDetails(sourceObj, panel);
        if (nxpair.containsKey(pd)) {
            Source source = nxpair.get(pd);
            return source.isRouteActive(getPointDetails(destObj, panel));
        }
        return false;
    }

    public void cancelInterlock(Object source, LayoutEditor panel, Object dest) {
        if (nxpair.containsKey(getPointDetails(source, panel))) {
            nxpair.get(getPointDetails(source, panel)).cancelInterlock(dest, panel);
        }

    }

    jmri.SignalMastLogicManager smlm = InstanceManager.getDefault(jmri.SignalMastLogicManager.class);

    public final static int CANCELROUTE = 0;
    public final static int CLEARROUTE = 1;
    public final static int EXITROUTE = 2;
    public final static int STACKROUTE = 4;

   /**
     * Return a point from a given LE Panel.
     *
     * @param obj The point object
     * @param panel The Layout Editor panel on which the point was placed
     * @return the point object, null if the point is not found
     */
    public PointDetails getPointDetails(Object obj, LayoutEditor panel) {
        for (int i = 0; i < pointDetails.size(); i++) {
            if ((pointDetails.get(i).getRefObject() == obj) && (pointDetails.get(i).getPanel() == panel)) {
                return pointDetails.get(i);

            }
        }
        return null;
    }

    /**
     * Return either an existing point stored in pointDetails, or create a new one as required.
     *
     * @param source The Layout Block functioning as the source (origin)
     * @param destination A (list of) Layout Blocks functioning as destinations
     * @param panel The Layout Editor panel on which the point is to be placed
     * @return the point object
     */
    PointDetails getPointDetails(LayoutBlock source, List<LayoutBlock> destination, LayoutEditor panel) {
        PointDetails newPoint = new PointDetails(source, destination);
        newPoint.setPanel(panel);
        for (int i = 0; i < pointDetails.size(); i++) {
            if (pointDetails.get(i).equals(newPoint)) {
                return pointDetails.get(i);

            }
        }
        //Not found so will add
        pointDetails.add(newPoint);
        return newPoint;
    }

    //No point can have multiple copies of what is the same thing.
    static ArrayList<PointDetails> pointDetails = new ArrayList<PointDetails>();

    /**
     * Get the name of a destinationPoint on a LE Panel.
     *
     * @param obj the point object
     * @param panel The Layout Editor panel on which it is expected to be placed
     * @return the name of the point
     */
    public String getPointAsString(NamedBean obj, LayoutEditor panel) {
        if (obj == null) {
            return "null";
        }
        PointDetails valid = getPointDetails(obj, panel);  //was just plain getPoint
        if (valid != null) {
            return valid.getDisplayName();
        }
        return "empty";
    }

    ArrayList<StackDetails> stackList = new ArrayList<StackDetails>();

    /**
     * If a route is requested but is currently blocked, ask user
     * if it should be added to stackList.
     *
     * @param dp DestinationPoints object
     * @param reverse true for a reversed running direction, mostly false
     */
    synchronized public void stackNXRoute(DestinationPoints dp, boolean reverse) {
        if (isRouteStacked(dp, reverse)) {
            return;
        }
        stackList.add(new StackDetails(dp, reverse));
        checkTimer.start();
        if (stackPanel == null) {
            stackPanel = new StackNXPanel();
        }
        if (stackDialog == null) {
            stackDialog = new JDialog();
            stackDialog.setTitle(Bundle.getMessage("WindowTitleStackRoutes"));
            stackDialog.add(stackPanel);
        }
        stackPanel.updateGUI();

        stackDialog.pack();
        stackDialog.setModal(false);
        stackDialog.setVisible(true);
    }

    StackNXPanel stackPanel = null;
    JDialog stackDialog = null;

    /**
     * Get a list of all stacked routes from stackList.
     *
     * @return an ArrayList containing destinationPoint elements
     */
    public List<DestinationPoints> getStackedInterlocks() {
        List<DestinationPoints> dpList = new ArrayList<DestinationPoints>();
        for (StackDetails st : stackList) {
            dpList.add(st.getDestinationPoint());
        }
        return dpList;
    }

    /**
     * Query if a stacked route is in stackList.
     *
     * @param dp DestinationPoints object
     * @param reverse true for a reversed running direction, mostly false
     * @return true if dp is in stackList
     */
    public boolean isRouteStacked(DestinationPoints dp, boolean reverse) {
        Iterator<StackDetails> iter = stackList.iterator();
        while (iter.hasNext()) {
            StackDetails st = iter.next();
            if (st.getDestinationPoint() == dp && st.getReverse() == reverse) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a stacked route from stackList.
     *
     * @param dp DestinationPoints object
     * @param reverse true for a reversed running direction, mostly false
     */
    synchronized public void cancelStackedRoute(DestinationPoints dp, boolean reverse) {
        Iterator<StackDetails> iter = stackList.iterator();
        while (iter.hasNext()) {
            StackDetails st = iter.next();
            if (st.getDestinationPoint() == dp && st.getReverse() == reverse) {
                iter.remove();
            }
        }
        stackPanel.updateGUI();
        if (stackList.isEmpty()) {
            stackDialog.setVisible(false);
            checkTimer.stop();
        }
    }

    /**
     * Class to collect (stack) routes when they are requested but blocked.
     */
    static class StackDetails {

        DestinationPoints dp;
        boolean reverse;

        StackDetails(DestinationPoints dp, boolean reverse) {
            this.dp = dp;
            this.reverse = reverse;
        }

        boolean getReverse() {
            return reverse;
        }

        DestinationPoints getDestinationPoint() {
            return dp;
        }
    }

    javax.swing.Timer checkTimer = new javax.swing.Timer(10000, new java.awt.event.ActionListener() {
        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            checkRoute();
        }
    });

    /**
     * Step through stackList and activate the first stacked route in line
     * if it is no longer blocked.
     */
    synchronized void checkRoute() {
        checkTimer.stop();
        StackDetails[] tmp = new StackDetails[stackList.size()];
        stackList.toArray(tmp);

        for (StackDetails st : tmp) {
            if (!st.getDestinationPoint().isActive()) {
                // If the route is not already active, then check.
                // If the route does get set, then the setting process will remove the route from the stack.
                st.getDestinationPoint().setInterlockRoute(st.getReverse());
            }
        }

        if (!stackList.isEmpty()) {
            checkTimer.start();
        } else {
            stackDialog.setVisible(false);
        }
    }

    public void removePropertyChangeListener(PropertyChangeListener list, NamedBean obj, LayoutEditor panel) {
        if (obj == null) {
            return;
        }
        PointDetails valid = getPointDetails(obj, panel);
        if (valid != null) {
            valid.removePropertyChangeListener(list);
        }
    }

    java.beans.PropertyChangeSupport pcs = new java.beans.PropertyChangeSupport(this);

    @Override
    public synchronized void addPropertyChangeListener(java.beans.PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public synchronized void removePropertyChangeListener(java.beans.PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    protected void firePropertyChange(String p, Object old, Object n) {
        pcs.firePropertyChange(p, old, n);
    }

    boolean runWhenStabilised = false;
    LayoutEditor toUseWhenStable;
    int interlockTypeToUseWhenStable;

    /**
     * Discover all possible valid source and destination Signal Mast Logic pairs
     * on all Layout Editor panels.
     *
     * @param editor The Layout Editor panel
     * @param interlockType Integer value representing the type of interlocking, one of
     *                      SETUPTURNOUTSONLY, SETUPSIGNALMASTLOGIC or FULLINTERLOCK
     * @throws JmriException when an error occurs during discovery
     */
    public void automaticallyDiscoverEntryExitPairs(LayoutEditor editor, int interlockType) throws JmriException {
        //This is almost a duplicate of that in the DefaultSignalMastLogicManager
        runWhenStabilised = false;
        jmri.jmrit.display.layoutEditor.LayoutBlockManager lbm = InstanceManager.getDefault(jmri.jmrit.display.layoutEditor.LayoutBlockManager.class);
        if (!lbm.isAdvancedRoutingEnabled()) {
            throw new JmriException("advanced routing not enabled");
        }
        if (!lbm.routingStablised()) {
            runWhenStabilised = true;
            toUseWhenStable = editor;
            interlockTypeToUseWhenStable = interlockType;
            log.debug("Layout block routing has not yet stabilised, discovery will happen once it has");
            return;
        }
        Hashtable<NamedBean, ArrayList<NamedBean>> validPaths = lbm.getLayoutBlockConnectivityTools().discoverValidBeanPairs(editor, Sensor.class, LayoutBlockConnectivityTools.SENSORTOSENSOR);
        Enumeration<NamedBean> en = validPaths.keys();
        EntryExitPairs eep = this;
        while (en.hasMoreElements()) {
            NamedBean key = en.nextElement();
            ArrayList<NamedBean> validDestMast = validPaths.get(key);
            if (validDestMast.size() > 0) {
                eep.addNXSourcePoint(key, editor);
                for (int i = 0; i < validDestMast.size(); i++) {
                    if (!eep.isDestinationValid(key, validDestMast.get(i), editor)) {
                        eep.addNXDestination(key, validDestMast.get(i), editor);
                        eep.setEntryExitType(key, editor, validDestMast.get(i), interlockType);
                    }
                }
            }
        }

        firePropertyChange("autoGenerateComplete", null, null);
    }

    protected PropertyChangeListener propertyBlockManagerListener = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent e) {
            if (e.getPropertyName().equals("topology")) {
                //boolean newValue = new Boolean.parseBoolean(String.valueOf(e.getNewValue()));
                boolean newValue = (Boolean) e.getNewValue();
                if (newValue) {
                    if (runWhenStabilised) {
                        try {
                            automaticallyDiscoverEntryExitPairs(toUseWhenStable, interlockTypeToUseWhenStable);
                        } catch (JmriException je) {
                            //Considered normal if routing not enabled
                        }
                    }
                }
            }
        }
    };

    public void vetoableChange(java.beans.PropertyChangeEvent evt) throws java.beans.PropertyVetoException {

    }

    java.beans.VetoableChangeSupport vcs = new java.beans.VetoableChangeSupport(this);

    @Override
    public synchronized void addVetoableChangeListener(java.beans.VetoableChangeListener l) {
        vcs.addVetoableChangeListener(l);
    }

    @Override
    public synchronized void removeVetoableChangeListener(java.beans.VetoableChangeListener l) {
        vcs.removeVetoableChangeListener(l);
    }

    @Override
    public void deleteBean(NamedBean bean, String property) throws java.beans.PropertyVetoException {

    }

    @Override
    public String getBeanTypeHandled() {
        return Bundle.getMessage("BeanNameTransit");
    }

    // initialize logging
    private final static Logger log = LoggerFactory.getLogger(EntryExitPairs.class.getName());
}
