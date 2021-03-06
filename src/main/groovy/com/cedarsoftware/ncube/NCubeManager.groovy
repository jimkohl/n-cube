package com.cedarsoftware.ncube

import com.cedarsoftware.ncube.util.CdnClassLoader
import com.cedarsoftware.util.ArrayUtilities
import com.cedarsoftware.util.CaseInsensitiveSet
import com.cedarsoftware.util.EncryptionUtilities
import com.cedarsoftware.util.IOUtilities
import com.cedarsoftware.util.MapUtilities
import com.cedarsoftware.util.StringUtilities
import com.cedarsoftware.util.SystemUtilities
import com.cedarsoftware.util.TrackingMap
import com.cedarsoftware.util.io.JsonObject
import com.cedarsoftware.util.io.JsonReader
import com.cedarsoftware.util.io.JsonWriter
import groovy.transform.CompileStatic
import ncube.grv.method.NCubeGroovyController
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.regex.Pattern

/**
 * This class manages a list of NCubes.  This class is referenced
 * by NCube in one place - when it joins to other cubes, it consults
 * the NCubeManager to find the joined NCube.
 * <p/>
 * This class takes care of creating, loading, updating, releasing,
 * and deleting NCubes.  It also allows you to get a list of NCubes
 * matching a wildcard (SQL Like) string.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either eƒfetxpress or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
class NCubeManager
{
    public static final String ERROR_CANNOT_MOVE_000 = 'Version 0.0.0 is for system configuration and cannot be move.'
    public static final String ERROR_CANNOT_MOVE_TO_000 = 'Version 0.0.0 is for system configuration and branch cannot be moved to it.'
    public static final String ERROR_CANNOT_RELEASE_000 = 'Version 0.0.0 is for system configuration and cannot be released.'
    public static final String ERROR_CANNOT_RELEASE_TO_000 = 'Version 0.0.0 is for system configuration and cannot be created from the release process.'
    public static final String ERROR_NOT_ADMIN = 'Operation not performed. You do not have admin permissions for '

    public static final String SEARCH_INCLUDE_CUBE_DATA = 'includeCubeData'
    public static final String SEARCH_INCLUDE_TEST_DATA = 'includeTestData'
    public static final String SEARCH_INCLUDE_NOTES = 'includeNotes'
    public static final String SEARCH_DELETED_RECORDS_ONLY = 'deletedRecordsOnly'
    public static final String SEARCH_ACTIVE_RECORDS_ONLY = 'activeRecordsOnly'
    public static final String SEARCH_CHANGED_RECORDS_ONLY = 'changedRecordsOnly'
    public static final String SEARCH_EXACT_MATCH_NAME = 'exactMatchName'

    public static final String SYS_BOOTSTRAP = 'sys.bootstrap'
    public static final String SYS_PROTOTYPE = 'sys.prototype'
    public static final String SYS_PERMISSIONS = 'sys.permissions'
    public static final String SYS_USERGROUPS = 'sys.usergroups'
    public static final String SYS_LOCK = 'sys.lock'
    public static final String SYS_BRANCH_PERMISSIONS = 'sys.branch.permissions'
    public static final String CLASSPATH_CUBE = 'sys.classpath'

    public static final String ROLE_ADMIN = 'admin'
    public static final String ROLE_USER = 'user'
    public static final String ROLE_READONLY = 'readonly'

    public static final String AXIS_ROLE = 'role'
    public static final String AXIS_USER = 'user'
    public static final String AXIS_RESOURCE = 'resource'
    public static final String AXIS_ACTION = 'action'
    public static final String AXIS_SYSTEM = 'system'

    public static final String PROPERTY_CACHE = 'cache'

    public static final int PERMISSION_CACHE_THRESHOLD = 1000 * 60 * 30 // half-hour

    // Maintain cache of 'wildcard' patterns to Compiled Pattern instance
    private static ConcurrentMap<String, Pattern> wildcards = new ConcurrentHashMap<>()
    private static final ConcurrentMap<ApplicationID, ConcurrentMap<String, Object>> ncubeCache = new ConcurrentHashMap<>()
    private static final ConcurrentMap<ApplicationID, ConcurrentMap<String, Advice>> advices = new ConcurrentHashMap<>()
    private static final ConcurrentMap<ApplicationID, GroovyClassLoader> localClassLoaders = new ConcurrentHashMap<>()
    static final String NCUBE_PARAMS = 'NCUBE_PARAMS'
    private static NCubePersister nCubePersister
    private static final Logger LOG = LogManager.getLogger(NCubeManager.class)

    // not private in case we want to tweak things for testing.
    protected static volatile ConcurrentMap<String, Object> systemParams = null

    private static final ThreadLocal<String> userId = new ThreadLocal<String>() {
        public String initialValue()
        {
            Map params = systemParams
            String userId = params.user instanceof String ? params.user : System.getProperty('user.name')
            return userId?.trim()
        }
    }

    // cache key = SHA-1(userId + '_' + appId + '_' + resource + '_' + Action)
    // cache value = Long (negative = false, positive = true, abs(value) = millis since last access)
    private static final Map<String, Long> permCache = new ConcurrentHashMap<>()


    private static final List CUBE_MUTATE_ACTIONS = [Action.COMMIT, Action.UPDATE]

    /**
     * Store the Persister to be used with the NCubeManager API (Dependency Injection API)
     */
    static void setNCubePersister(NCubePersister persister)
    {
        nCubePersister = persister
    }

    static NCubePersister getPersister()
    {
        if (nCubePersister == null)
        {
            throw new IllegalStateException('Persister not set into NCubeManager.')
        }
        return nCubePersister
    }

    static Map<String, Object> getSystemParams()
    {
        final ConcurrentMap<String, Object> params = systemParams

        if (params != null)
        {
            return params
        }

        synchronized (NCubeManager.class)
        {
            if (systemParams == null)
            {
                String jsonParams = SystemUtilities.getExternalVariable(NCUBE_PARAMS)
                ConcurrentMap sysParamMap = new ConcurrentHashMap<>()

                if (StringUtilities.hasContent(jsonParams))
                {
                    try
                    {
                        sysParamMap = new ConcurrentHashMap<>((Map) JsonReader.jsonToJava(jsonParams, [(JsonReader.USE_MAPS): true] as Map))
                    }
                    catch (Exception ignored)
                    {
                        LOG.warn('Parsing of NCUBE_PARAMS failed. ' + jsonParams)
                    }
                }
                systemParams = sysParamMap
            }
        }
        return systemParams
    }

    /**
     * Fetch all the n-cube names for the given ApplicationID.  This API
     * will load all cube records for the ApplicationID (NCubeInfoDtos),
     * and then get the names from them.
     *
     * @return Set < String >  n-cube names.  If an empty Set is returned,
     * then there are no persisted n-cubes for the passed in ApplicationID.
     */
    @Deprecated
    protected static Set<String> getCubeNames(ApplicationID appId)
    {
        List<NCubeInfoDto> cubeInfos = search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY): true])
        Set<String> names = new TreeSet<>()

        for (NCubeInfoDto info : cubeInfos)
        {   // Permission check happened in search()
            names.add(info.name)
        }

        if (names.isEmpty())
        {   // Support tests that load cubes from JSON files...
            // can only be in there as ncubes, not ncubeDtoInfo
            for (Object value : getCacheForApp(appId).values())
            {
                if (value instanceof NCube)
                {
                    NCube cube = (NCube) value
                    names.add(cube.name)
                }
            }
        }
        return new CaseInsensitiveSet<>(names)
    }

    /**
     * Load n-cube, bypassing any caching.  This is necessary for n-cube-editor (IDE time
     * usage).  If the IDE environment is clustered, cannot be getting stale copies from
     * cache.  Any advices in the manager will be applied to the n-cube.
     * @return NCube of the specified name from the specified AppID, or null if not found.
     */
    static NCube loadCube(ApplicationID appId, String cubeName)
    {
        assertPermissions(appId, cubeName)
        NCube ncube = persister.loadCube(appId, cubeName)
        if (ncube == null)
        {
            return null
        }
        applyAdvices(ncube.applicationID, ncube)
        cacheCube(appId, ncube)
        return ncube
    }

    /**
     * Fetch an n-cube by name from the given ApplicationID.  If no n-cubes
     * are loaded, then a loadCubes() call is performed and then the
     * internal cache is checked again.  If the cube is not found, null is
     * returned.
     */
    static NCube getCube(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        assertPermissions(appId, cubeName)
        NCube.validateCubeName(cubeName)
        return getCubeInternal(appId, cubeName)
    }

    private static NCube getCubeInternal(ApplicationID appId, String cubeName)
    {
        Map<String, Object> cubes = getCacheForApp(appId)
        final String lowerCubeName = cubeName.toLowerCase()

        if (cubes.containsKey(lowerCubeName))
        {   // pull from cache
            final Object cube = cubes[lowerCubeName]
            return Boolean.FALSE == cube ? null : cube as NCube
        }

        // now even items with metaProperties(cache = 'false') can be retrieved
        // and normal app processing doesn't do two queries anymore.
        // used to do getCubeInfoRecords() -> dto
        // and then dto -> loadCube(id)
        NCube ncube = persister.loadCube(appId, cubeName)
        if (ncube == null)
        {   // Associate 'failed to load' with Boolean.FALSE so no further attempts are made to load it
            cubes[lowerCubeName] = Boolean.FALSE
            return null
        }
        return prepareCube(ncube)
    }

    private static NCube prepareCube(NCube cube)
    {
        applyAdvices(cube.applicationID, cube)
        cacheCube(cube.applicationID, cube)
        return cube
    }

    /**
     * Load the n-cube with the specified id.  This is useful in n-cube editors, where a user wants to pick
     * an older revision and load / compare it.
     * @param id long n-cube id.
     * @return NCube that has the passed in id.
     */
    static NCube loadCubeById(long id)
    {
        NCube ncube = persister.loadCubeById(id)
        return prepareCube(ncube)
    }

    /**
     * Fetch the classloader for the given ApplicationID.
     */
    protected static URLClassLoader getUrlClassLoader(ApplicationID appId, Map input)
    {
        NCube cpCube = getCube(appId, CLASSPATH_CUBE)

        if (cpCube == null)
        {   // No sys.classpath cube exists, just create regular GroovyClassLoader with no URLs set into it.
            // Scope the GroovyClassLoader per ApplicationID
            return getLocalClassloader(appId)
        }

        final String envLevel = SystemUtilities.getExternalVariable('ENV_LEVEL')
        if (StringUtilities.hasContent(envLevel) && !doesMapContainKey(input, 'env'))
        {   // Add in the 'ENV_LEVEL" environment variable when looking up sys.* cubes,
            // if there was not already an entry for it.
            input.env = envLevel
        }
        if (!doesMapContainKey(input, 'username'))
        {   // same as ENV_LEVEL, add it in if not already there.
            input.username = System.getProperty('user.name')
        }
        Object urlCpLoader = cpCube.getCell(input)

        if (urlCpLoader instanceof URLClassLoader)
        {
            return (URLClassLoader)urlCpLoader
        }

        throw new IllegalStateException('If the sys.classpath cube exists, it must return a URLClassLoader.')
    }

    private static boolean doesMapContainKey(Map map, String key)
    {
        if (map instanceof TrackingMap)
        {
            Map wrappedMap = ((TrackingMap)map).getWrappedMap()
            return wrappedMap.containsKey(key)
        }
        return map.containsKey(key)
    }

    protected static URLClassLoader getLocalClassloader(ApplicationID appId)
    {
        GroovyClassLoader gcl = localClassLoaders[appId]
        if (gcl == null)
        {
            gcl = new CdnClassLoader(NCubeManager.class.classLoader)
            GroovyClassLoader classLoaderRef = localClassLoaders.putIfAbsent(appId, gcl)
            if (classLoaderRef != null)
            {
                gcl = classLoaderRef
            }
        }
        return gcl
    }

    /**
     * Add a cube to the internal cache of available cubes.
     * @param ncube NCube to add to the list.
     */
    static void addCube(ApplicationID appId, NCube ncube)
    {
        validateAppId(appId)
        validateCube(ncube)

        // Apply any matching advices to it
        applyAdvices(appId, ncube)
        cacheCube(appId, ncube)
    }

    /**
     * Fetch the Map of n-cubes for the given ApplicationID.  If no
     * cache yet exists, a new empty cache is added.
     */
    protected static Map<String, Object> getCacheForApp(ApplicationID appId)
    {
        ConcurrentMap<String, Object> ncubes = ncubeCache[appId]

        if (ncubes == null)
        {
            ncubes = new ConcurrentHashMap<>()
            ConcurrentMap<String, Object> mapRef = ncubeCache.putIfAbsent(appId, ncubes)
            if (mapRef != null)
            {
                ncubes = mapRef
            }
        }
        return ncubes
    }

    static void clearCacheForBranches(ApplicationID appId)
    {
        synchronized (ncubeCache)
        {
            Set<ApplicationID> set = [] as Set

            for (ApplicationID id : ncubeCache.keySet())
            {
                if (id.cacheKey().startsWith(appId.branchAgnosticCacheKey()))
                {
                    set.add(id)
                }
            }

            for (ApplicationID appId1 : set)
            {
                clearCache(appId1)
            }
        }
    }

    /**
     * Clear the cube (and other internal caches) for a given ApplicationID.
     * This will remove all the n-cubes from memory, compiled Groovy code,
     * caches related to expressions, caches related to method support,
     * advice caches, and local classes loaders (used when no sys.classpath is
     * present).
     *
     * @param appId ApplicationID for which the cache is to be cleared.
     */
    static void clearCache(ApplicationID appId)
    {
        synchronized (ncubeCache)
        {
            validateAppId(appId)

            Map<String, Object> appCache = getCacheForApp(appId)
            clearGroovyClassLoaderCache(appCache)

            appCache.clear()
            GroovyBase.clearCache(appId)
            NCubeGroovyController.clearCache(appId)

            // Clear Advice cache
            Map<String, Advice> adviceCache = advices[appId]
            if (adviceCache != null)
            {
                adviceCache.clear()
            }

            // Clear ClassLoader cache
            GroovyClassLoader classLoader = localClassLoaders[appId]
            if (classLoader != null)
            {
                classLoader.clearCache()
                localClassLoaders.remove(appId)
            }
            broadcast(appId)
        }
    }

    /**
     * This method will clear all caches for all ApplicationIDs.
     * Do not call it for anything other than test purposes.
     */
    static void clearCache()
    {
        synchronized (ncubeCache)
        {
            List<ApplicationID> list = []

            for (ApplicationID appId : ncubeCache.keySet())
            {
                list.add(appId)
            }

            for (ApplicationID appId1 : list)
            {
                clearCache(appId1)
            }
        }
    }

    private static void clearGroovyClassLoaderCache(Map<String, Object> appCache)
    {
        Object cube = appCache[CLASSPATH_CUBE]
        if (cube instanceof NCube)
        {
            NCube cpCube = cube as NCube
            for (Object content : cpCube.cellMap.values())
            {
                if (content instanceof UrlCommandCell)
                {
                    ((UrlCommandCell)content).clearClassLoaderCache()
                }
            }
        }
    }

    /**
     * Associate Advice to all n-cubes that match the passed in regular expression.
     */
    static void addAdvice(ApplicationID appId, String wildcard, Advice advice)
    {
        validateAppId(appId)
        ConcurrentMap<String, Advice> current = advices[appId]
        if (current == null)
        {
            current = new ConcurrentHashMap<>()
            ConcurrentMap<String, Advice> mapRef = advices.putIfAbsent(appId, current)
            if (mapRef != null)
            {
                current = mapRef
            }
        }

        current[advice.name + '/' + wildcard] = advice

        // Apply newly added advice to any fully loaded (hydrated) cubes.
        String regex = StringUtilities.wildcardToRegexString(wildcard)
        Pattern pattern = Pattern.compile(regex)
        Map<String, Object> cubes = getCacheForApp(appId)

        for (Object value : cubes.values())
        {
            if (value instanceof NCube)
            {   // apply advice to hydrated cubes
                NCube ncube = value as NCube
                Axis axis = ncube.getAxis('method')
                addAdviceToMatchedCube(advice, pattern, ncube, axis)
            }
        }
    }

    private static void addAdviceToMatchedCube(Advice advice, Pattern pattern, NCube ncube, Axis axis)
    {
        if (axis != null)
        {   // Controller methods
            for (Column column : axis.columnsWithoutDefault)
            {
                String method = column.value.toString()
                String classMethod = ncube.name + '.' + method + '()'
                if (pattern.matcher(classMethod).matches())
                {
                    ncube.addAdvice(advice, method)
                }
            }
        }

        // Add support for run() method (inline GroovyExpressions)
        String classMethod = ncube.name + '.run()'
        if (pattern.matcher(classMethod).matches())
        {
            ncube.addAdvice(advice, 'run')
        }
    }

    /**
     * Apply existing advices loaded into the NCubeManager, to the passed in
     * n-cube.  This allows advices to be added first, and then let them be
     * applied 'on demand' as an n-cube is loaded later.
     * @param appId ApplicationID
     * @param ncube NCube to which all matching advices will be applied.
     */
    private static void applyAdvices(ApplicationID appId, NCube ncube)
    {
        final Map<String, Advice> appAdvices = advices[appId]

        if (MapUtilities.isEmpty(appAdvices))
        {
            return
        }
        for (Map.Entry<String, Advice> entry : appAdvices.entrySet())
        {
            final Advice advice = entry.value
            final String wildcard = entry.key.replace(advice.name + '/', "")
            final String regex = StringUtilities.wildcardToRegexString(wildcard)
            final Axis axis = ncube.getAxis('method')
            addAdviceToMatchedCube(advice, Pattern.compile(regex), ncube, axis)
        }
    }

    /**
     * Retrieve all cube names that are deeply referenced by ApplicationID + n-cube name.
     */
    static void getReferencedCubeNames(ApplicationID appId, String name, Set<String> refs)
    {
        if (refs == null)
        {
            throw new IllegalArgumentException('Could not get referenced cube names, null passed in for Set to hold referenced n-cube names, app: ' + appId + ', n-cube: ' + name)
        }
        validateAppId(appId)
        NCube.validateCubeName(name)
        NCube ncube = getCube(appId, name)
        if (ncube == null)
        {
            throw new IllegalArgumentException('Could not get referenced cube names, n-cube: ' + name + ' does not exist in app: ' + appId)
        }
        Set<String> subCubeList = ncube.referencedCubeNames

        // TODO: Use explicit stack, NOT recursion

        for (String cubeName : subCubeList)
        {
            if (!refs.contains(cubeName))
            {
                refs.add(cubeName)
                getReferencedCubeNames(appId, cubeName, refs)
            }
        }
    }

    /**
     * Restore a previously deleted n-cube.
     */
    static void restoreCubes(ApplicationID appId, Object[] cubeNames)
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()

        if (appId.release)
        {
            throw new IllegalArgumentException(ReleaseStatus.RELEASE.name() + ' cubes cannot be restored, app: ' + appId)
        }

        if (ArrayUtilities.isEmpty(cubeNames))
        {
            throw new IllegalArgumentException('Error, empty array of cube names passed in to be restored.')
        }

        assertNotLockBlocked(appId)
        for (String cubeName : cubeNames)
        {
            assertPermissions(appId, cubeName, Action.UPDATE)
        }

        // Batch restore
        persister.restoreCubes(appId, cubeNames, getUserId())

        // Load cache
        for (Object name : cubeNames)
        {
            if ((name instanceof String))
            {
                String cubeName = name as String
                NCube.validateCubeName(cubeName)
                NCube ncube = persister.loadCube(appId, cubeName)
                addCube(appId, ncube)
            }
            else
            {
                throw new IllegalArgumentException('Non string name given for cube to restore: ' + name)
            }
        }
    }

    /**
     * Get a List<NCubeInfoDto> containing all history for the given cube.
     */
    static List<NCubeInfoDto> getRevisionHistory(ApplicationID appId, String cubeName, boolean ignoreVersion = false)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName)
        List<NCubeInfoDto> revisions = persister.getRevisions(appId, cubeName, ignoreVersion)
        return revisions
    }

    /**
     * Return a List of Strings containing all unique App names for the given tenant.
     */
    static List<String> getAppNames(String tenant)
    {
        return persister.getAppNames(tenant)
    }

    /**
     * Get all of the versions that exist for the given ApplicationID (tenant and app).
     * @return List<String> version numbers.
     */
    static Map<String, List<String>> getVersions(String tenant, String app)
    {
        ApplicationID.validateTenant(tenant)
        ApplicationID.validateApp(app)
        return persister.getVersions(tenant, app)
    }

    /**
     * Duplicate the given n-cube specified by oldAppId and oldName to new ApplicationID and name,
     */
    static void duplicate(ApplicationID oldAppId, ApplicationID newAppId, String oldName, String newName)
    {
        validateAppId(oldAppId)
        validateAppId(newAppId)

        newAppId.validateBranchIsNotHead()

        if (newAppId.release)
        {
            throw new IllegalArgumentException('Cubes cannot be duplicated into a ' + ReleaseStatus.RELEASE + ' version, cube: ' + newName + ', app: ' + newAppId)
        }

        NCube.validateCubeName(oldName)
        NCube.validateCubeName(newName)

        if (oldName.equalsIgnoreCase(newName) && oldAppId == newAppId)
        {
            throw new IllegalArgumentException('Could not duplicate, old name cannot be the same as the new name when oldAppId matches newAppId, name: ' + oldName + ', app: ' + oldAppId)
        }

        assertPermissions(oldAppId, oldName, Action.READ)
        if (oldAppId != newAppId)
        {   // Only see if branch permissions are needed to be created when destination cube is in a different ApplicationID
            detectNewAppId(newAppId)
        }
        assertPermissions(newAppId, newName, Action.UPDATE)
        assertNotLockBlocked(newAppId)
        persister.duplicateCube(oldAppId, newAppId, oldName, newName, getUserId())

        if (CLASSPATH_CUBE.equalsIgnoreCase(newName))
        {   // If another cube is renamed into sys.classpath,
            // then the entire class loader must be dropped (and then lazily rebuilt).
            clearCache(newAppId)
        }
        else
        {
            removeCachedCube(newAppId, newName)
        }
    }

    /**
     * Update the passed in NCube.  Only SNAPSHOT cubes can be updated.
     *
     * @param ncube      NCube to be updated.
     * @return boolean true on success, false otherwise
     */
    static boolean updateCube(ApplicationID appId, NCube ncube, boolean createPermCubesIfNeeded = false)
    {
        validateAppId(appId)
        validateCube(ncube)

        if (appId.release)
        {
            throw new IllegalArgumentException(ReleaseStatus.RELEASE.name() + ' cubes cannot be updated, cube: ' + ncube.name + ', app: ' + appId)
        }

        appId.validateBranchIsNotHead()

        final String cubeName = ncube.name
        if (createPermCubesIfNeeded)
        {
            detectNewAppId(appId)
        }
        assertPermissions(appId, cubeName, Action.UPDATE)
        assertNotLockBlocked(appId)
        persister.updateCube(appId, ncube, getUserId())
        ncube.applicationID = appId

        if (CLASSPATH_CUBE.equalsIgnoreCase(cubeName))
        {   // If the sys.classpath cube is changed, then the entire class loader must be dropped.  It will be lazily rebuilt.
            clearCache(appId)
        }

        addCube(appId, ncube)
        return true
    }

    /**
     * Copy branch from one app id to another
     * @param srcAppId Branch copied from (source branch)
     * @param targetAppId Branch copied to (must not exist)
     * @return int number of n-cubes in branch (number copied - revision depth is not copied)
     */
    static int copyBranch(ApplicationID srcAppId, ApplicationID targetAppId, boolean copyWithHistory = false)
    {
        validateAppId(srcAppId)
        validateAppId(targetAppId)
        targetAppId.validateStatusIsNotRelease()
        if (!search(targetAppId.asRelease(), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY): true]).empty)
        {
            throw new IllegalArgumentException("A RELEASE version " + targetAppId.version + " already exists, app: " + targetAppId)
        }
        assertNotLockBlocked(targetAppId)
        if (targetAppId.version != '0.0.0')
        {
            detectNewAppId(targetAppId)
        }
        int rows = copyWithHistory ? persister.copyBranchWithHistory(srcAppId, targetAppId) : persister.copyBranch(srcAppId, targetAppId)
        clearCache(targetAppId)
        return rows
    }

    /**
     * Merge the passed in List of Delta's into the named n-cube.
     * @param appId ApplicationID containing the named n-cube.
     * @param cubeName String name of the n-cube into which the Delta's will be merged.
     * @param deltas List of Delta instances
     * @return the NCube t
     */
    static NCube mergeDeltas(ApplicationID appId, String cubeName, List<Delta> deltas)
    {
        NCube ncube = getCube(appId, cubeName)
        if (ncube == null)
        {
            throw new IllegalArgumentException('No ncube exists with the name: ' + cubeName + ', no changes will be merged, app: ' + appId)
        }
        ncube.mergeDeltas(deltas)
        updateCube(appId, ncube)
        return ncube
    }

    /**
     * Move the branch specified in the appId to the newer snapshot version (newSnapVer).
     * @param ApplicationID indicating what to move
     * @param newSnapVer String version to move cubes to
     * @return number of rows moved (count includes revisions per cube).
     */
    static int moveBranch(ApplicationID appId, String newSnapVer)
    {
        validateAppId(appId)
        if (ApplicationID.HEAD == appId.branch)
        {
            throw new IllegalArgumentException('Cannot move the HEAD branch')
        }
        if ('0.0.0' == appId.version)
        {
            throw new IllegalStateException(ERROR_CANNOT_MOVE_000)
        }
        if ('0.0.0' == newSnapVer)
        {
            throw new IllegalStateException(ERROR_CANNOT_MOVE_TO_000)
        }
        assertLockedByMe(appId)
        assertPermissions(appId, null, Action.RELEASE)
        int rows = persister.moveBranch(appId, newSnapVer)
        clearCacheForBranches(appId)
        return rows
    }

    /**
     * Perform release (SNAPSHOT to RELEASE) for the given ApplicationIDs n-cubes.
     */
    static int releaseVersion(ApplicationID appId, String newSnapVer)
    {
        validateAppId(appId)
        assertPermissions(appId, null, Action.RELEASE)
        assertLockedByMe(appId)
        ApplicationID.validateVersion(newSnapVer)
        if ('0.0.0' == appId.version)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_000)
        }
        if ('0.0.0' == newSnapVer)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_TO_000)
        }
        if (search(appId.asRelease(), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true]).size() != 0)
        {
            throw new IllegalArgumentException("A RELEASE version " + appId.version + " already exists, app: " + appId)
        }

        int rows = persister.releaseCubes(appId, newSnapVer)
        clearCacheForBranches(appId)
        return rows
    }

    /**
     * Perform release (SNAPSHOT to RELEASE) for the given ApplicationIDs n-cubes.
     */
    static int releaseCubes(ApplicationID appId, String newSnapVer)
    {
        assertPermissions(appId, null, Action.RELEASE)
        validateAppId(appId)
        ApplicationID.validateVersion(newSnapVer)
        if ('0.0.0' == appId.version)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_000)
        }
        if ('0.0.0' == newSnapVer)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_TO_000)
        }
        if (search(appId.asVersion(newSnapVer), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true]).size() != 0)
        {
            throw new IllegalArgumentException("A SNAPSHOT version " + appId.version + " already exists, app: " + appId)
        }
        if (search(appId.asRelease(), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true]).size() != 0)
        {
            throw new IllegalArgumentException("A RELEASE version " + appId.version + " already exists, app: " + appId)
        }

        lockApp(appId)
        if (!isJUnitTest())
        {   // Only sleep when running in production (not by JUnit)
            sleep(10000)
        }

        Set<String> branches = getBranches(appId)
        for (String branch : branches)
        {
            if (!ApplicationID.HEAD.equalsIgnoreCase(branch))
            {
                ApplicationID branchAppId = appId.asBranch(branch)
                moveBranch(branchAppId, newSnapVer)
            }
        }
        int rows = persister.releaseCubes(appId, newSnapVer)
        persister.copyBranch(appId.asRelease(), appId.asSnapshot().asHead().asVersion(newSnapVer))
        clearCacheForBranches(appId)
        unlockApp(appId)
        return rows
    }

    private static boolean isJUnitTest()
    {
        StackTraceElement[] stackTrace = Thread.currentThread().stackTrace
        List<StackTraceElement> list = Arrays.asList(stackTrace)
        for (StackTraceElement element : list)
        {
            if (element.className.startsWith('org.junit.'))
            {
                return true
            }
        }
        return false
    }

    static void changeVersionValue(ApplicationID appId, String newVersion)
    {
        validateAppId(appId)

        if (appId.release)
        {
            throw new IllegalArgumentException('Cannot change the version of a ' + ReleaseStatus.RELEASE.name() + ' app, app: ' + appId)
        }
        ApplicationID.validateVersion(newVersion)
        assertPermissions(appId, null, Action.RELEASE)
        assertNotLockBlocked(appId)
        persister.changeVersionValue(appId, newVersion)
        clearCache(appId)
        clearCache(appId.asVersion(newVersion))
    }

    static boolean renameCube(ApplicationID appId, String oldName, String newName)
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()

        if (appId.release)
        {
            throw new IllegalArgumentException('Cannot rename a ' + ReleaseStatus.RELEASE.name() + ' cube, cube: ' + oldName + ', app: ' + appId)
        }

        assertNotLockBlocked(appId)

        NCube.validateCubeName(oldName)
        NCube.validateCubeName(newName)

        if (oldName == newName)
        {
            throw new IllegalArgumentException('Could not rename, old name cannot be the same as the new name, name: ' + oldName + ', app: ' + appId)
        }

        assertPermissions(appId, oldName, Action.UPDATE)
        assertPermissions(appId, newName, Action.UPDATE)

        boolean result = persister.renameCube(appId, oldName, newName, getUserId())

        if (CLASSPATH_CUBE.equalsIgnoreCase(oldName) || CLASSPATH_CUBE.equalsIgnoreCase(newName))
        {   // If the sys.classpath cube is renamed, or another cube is renamed into sys.classpath,
            // then the entire class loader must be dropped (and then lazily rebuilt).
            clearCache(appId)
        }
        else
        {
            removeCachedCube(appId, oldName)
            removeCachedCube(appId, newName)
        }

        return result
    }

    static boolean deleteBranch(ApplicationID appId)
    {
        appId.validateBranchIsNotHead()
        assertPermissions(appId, null, Action.UPDATE)
        assertNotLockBlocked(appId)
        return persister.deleteBranch(appId)
    }

    /**
     * Delete the named NCube from the database
     *
     * @param cubeNames  Object[] of String cube names to be deleted (soft deleted)
     */
    static boolean deleteCubes(ApplicationID appId, Object[] cubeNames)
    {
        appId.validateBranchIsNotHead()
        assertNotLockBlocked(appId)
        for (Object name : cubeNames)
        {
            assertPermissions(appId, name as String, Action.UPDATE)
        }
        return deleteCubes(appId, cubeNames, false)
    }

    protected static boolean deleteCubes(ApplicationID appId, Object[] cubeNames, boolean allowDelete)
    {
        validateAppId(appId)
        if (!allowDelete)
        {
            if (appId.release)
            {
                throw new IllegalArgumentException(ReleaseStatus.RELEASE.name() + ' cubes cannot be hard-deleted, app: ' + appId)
            }
        }

        assertNotLockBlocked(appId)
        for (Object name : cubeNames)
        {
            assertPermissions(appId, name as String, Action.UPDATE)
        }

        if (persister.deleteCubes(appId, cubeNames, allowDelete, getUserId()))
        {
            for (int i=0; i < cubeNames.length; i++)
            {
                removeCachedCube(appId, cubeNames[i] as String)
            }
            return true
        }
        return false
    }

    static boolean updateTestData(ApplicationID appId, String cubeName, String testData)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName, Action.UPDATE)
        assertNotLockBlocked(appId)
        return persister.updateTestData(appId, cubeName, testData)
    }

    static String getTestData(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName)
        return persister.getTestData(appId, cubeName)
    }

    static boolean updateNotes(ApplicationID appId, String cubeName, String notes)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName, Action.UPDATE)
        assertNotLockBlocked(appId)
        return persister.updateNotes(appId, cubeName, notes)
    }

    static String getNotes(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName)

        Map<String, Object> options = [:]
        options[SEARCH_INCLUDE_NOTES] = true
        options[SEARCH_EXACT_MATCH_NAME] = true
        List<NCubeInfoDto> infos = search(appId, cubeName, null, options)

        if (infos.empty)
        {
            throw new IllegalArgumentException('Could not fetch notes, no cube: ' + cubeName + ' in app: ' + appId)
        }
        return infos[0].notes
    }

    static Set<String> getBranches(ApplicationID appId)
    {
        appId.validate()
        assertPermissions(appId, null)
        return persister.getBranches(appId)
    }

    static int getBranchCount(ApplicationID appId)
    {
        Set<String> branches = getBranches(appId)
        return branches.size()
    }

    static ApplicationID getApplicationID(String tenant, String app, Map<String, Object> coord)
    {
        ApplicationID.validateTenant(tenant)
        ApplicationID.validateApp(tenant)

        if (coord == null)
        {
            coord = [:]
        }

        NCube bootCube = getCube(ApplicationID.getBootVersion(tenant, app), SYS_BOOTSTRAP)

        if (bootCube == null)
        {
            throw new IllegalStateException('Missing ' + SYS_BOOTSTRAP + ' cube in the 0.0.0 version for the app: ' + app)
        }

        ApplicationID bootAppId = (ApplicationID) bootCube.getCell(coord)
        String version = bootAppId.version
        String status = bootAppId.status
        String branch = bootAppId.branch

        if (!tenant.equalsIgnoreCase(bootAppId.tenant))
        {
            LOG.warn("sys.bootstrap cube for tenant '" + tenant + "', app '" + app + "' is returning a different tenant '" + bootAppId.tenant + "' than requested. Using '" + tenant + "' instead.")
        }

        if (!app.equalsIgnoreCase(bootAppId.app))
        {
            LOG.warn("sys.bootstrap cube for tenant '" + tenant + "', app '" + app + "' is returning a different app '" + bootAppId.app + "' than requested. Using '" + app + "' instead.")
        }

        return new ApplicationID(tenant, app, version, status, branch)
    }

    /**
     *
     * Fetch an array of NCubeInfoDto's where the cube names match the cubeNamePattern (contains) and
     * the content (in JSON format) 'contains' the passed in content String.
     * @param appId ApplicationID on which we are working
     * @param cubeNamePattern cubeNamePattern String pattern to match cube names
     * @param content String value that is 'contained' within the cube's JSON
     * @param options map with possible keys:
     *                changedRecordsOnly - default false ->  Only searches changed records if true.
     *                activeRecordsOnly - default false -> Only searches non-deleted records if true.
     *                deletedRecordsOnly - default false -> Only searches deleted records if true.
     *                cacheResult - default false -> Cache the cubes that match this result..
     * @return List<NCubeInfoDto>
     */
    static List<NCubeInfoDto> search(ApplicationID appId, String cubeNamePattern, String content, Map options)
    {
        validateAppId(appId)

        if (options == null)
        {
            options = [:]
        }

        if (!options[SEARCH_EXACT_MATCH_NAME])
        {
            cubeNamePattern = handleWildCard(cubeNamePattern)
        }

        content = handleWildCard(content)

        Map permInfo = getPermInfo(appId)
        List<NCubeInfoDto> cubes = persister.search(appId, cubeNamePattern, content, options)
        if (!permInfo.skipPermCheck)
        {
            cubes.removeAll { !fastCheckPermissions(appId, it.name, Action.READ, permInfo) }
        }
        return cubes
    }

    /**
     * This API will hand back a List of AxisRef, which is a complete description of a Reference
     * Axis pointer. It includes the Source ApplicationID, source Cube Name, source Axis Name,
     * and all the referenced cube/axis and filter (cube/method) parameters.
     * @param appId ApplicationID of the cube-set from which to fetch all the reference axes.
     * @return List<AxisRef>
     */
    static List<AxisRef> getReferenceAxes(ApplicationID appId)
    {
        validateAppId(appId)
        assertPermissions(appId, null)

        // Step 1: Fetch all NCubeInfoDto's for the passed in ApplicationID
        List<NCubeInfoDto> list = persister.search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true])
        List<AxisRef> refAxes = []

        for (NCubeInfoDto dto : list)
        {
            try
            {
                NCube source = persister.loadCubeById(dto.id as long)
                for (Axis axis : source.axes)
                {
                    if (axis.reference)
                    {
                        AxisRef ref = new AxisRef()
                        ref.srcAppId = appId
                        ref.srcCubeName = source.name
                        ref.srcAxisName = axis.name

                        ApplicationID refAppId = axis.referencedApp
                        ref.destApp = refAppId.app
                        ref.destVersion = refAppId.version
                        ref.destCubeName = axis.getMetaProperty(ReferenceAxisLoader.REF_CUBE_NAME)
                        ref.destAxisName = axis.getMetaProperty(ReferenceAxisLoader.REF_AXIS_NAME)

                        ApplicationID transformAppId = axis.transformApp
                        if (transformAppId)
                        {
                            ref.transformApp = transformAppId.app
                            ref.transformVersion = transformAppId.version
                            ref.transformCubeName = axis.getMetaProperty(ReferenceAxisLoader.TRANSFORM_CUBE_NAME)
                            ref.transformMethodName = axis.getMetaProperty(ReferenceAxisLoader.TRANSFORM_METHOD_NAME)
                        }

                        refAxes.add(ref)
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn('Unable to load cube: ' + dto.name + ', app: ' + dto.applicationID, e)
            }
        }
        return refAxes
    }

    static void updateReferenceAxes(List<AxisRef> axisRefs)
    {
        Set<ApplicationID> uniqueAppIds = new HashSet()
        for (AxisRef axisRef : axisRefs)
        {
            ApplicationID srcApp = axisRef.srcAppId
            validateAppId(srcApp)
            assertPermissions(srcApp, axisRef.srcCubeName, Action.UPDATE)
            uniqueAppIds.add(srcApp)
            ApplicationID destAppId = new ApplicationID(srcApp.tenant, axisRef.destApp, axisRef.destVersion, ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)
            validateAppId(destAppId)
            assertPermissions(destAppId, axisRef.destCubeName)

            if (axisRef.transformApp != null && axisRef.transformVersion != null)
            {
                ApplicationID transformAppId = new ApplicationID(srcApp.tenant, axisRef.transformApp, axisRef.transformVersion, ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)
                validateAppId(transformAppId)
                assertPermissions(transformAppId, axisRef.transformCubeName, Action.READ)
            }
            removeCachedCube(srcApp, axisRef.srcCubeName)
        }

        // Make sure we are not lock blocked on any of the appId's that are being updated.
        for (ApplicationID appId : uniqueAppIds)
        {
            assertNotLockBlocked(appId)
        }

        for (AxisRef axisRef : axisRefs)
        {
            axisRef.with {
                NCube ncube = persister.loadCube(srcAppId, srcCubeName)
                Axis axis = ncube.getAxis(srcAxisName)

                if (axis.reference)
                {
                    axis.setMetaProperty(ReferenceAxisLoader.REF_APP, destApp)
                    axis.setMetaProperty(ReferenceAxisLoader.REF_VERSION, destVersion)
                    axis.setMetaProperty(ReferenceAxisLoader.REF_CUBE_NAME, destCubeName)
                    axis.setMetaProperty(ReferenceAxisLoader.REF_AXIS_NAME, destAxisName)
                    ApplicationID appId = new ApplicationID(srcAppId.tenant, destApp, destVersion, ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)

                    NCube target = persister.loadCube(appId, destCubeName)
                    if (target == null)
                    {
                        throw new IllegalArgumentException('Cannot point reference axis to non-existing cube (' +
                                destCubeName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                ', target: ' + destApp + ' / ' + destVersion + ' / ' + destCubeName + '.' + destAxisName)
                    }

                    if (target.getAxis(destAxisName) == null)
                    {
                        throw new IllegalArgumentException('Cannot point reference axis to non-existing axis (' +
                                destAxisName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                ', target: ' + destApp + ' / ' + destVersion + ' / ' + destCubeName + '.' + destAxisName)
                    }

                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_APP, transformApp)
                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_VERSION, transformVersion)
                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_CUBE_NAME, transformCubeName)
                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_METHOD_NAME, transformMethodName)

                    if (transformApp && transformVersion && transformCubeName && transformMethodName)
                    {   // If transformer cube reference supplied, verify that the cube exists
                        ApplicationID txAppId = new ApplicationID(srcAppId.tenant, transformApp, transformVersion, ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)
                        NCube transformCube = persister.loadCube(txAppId, transformCubeName)
                        if (transformCube == null)
                        {
                            throw new IllegalArgumentException('Cannot point reference axis transformer to non-existing cube (' +
                                    transformCubeName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                    ', target: ' + transformApp + ' / ' + transformVersion + ' / ' + transformCubeName + '.' + transformMethodName)
                        }

                        if (transformCube.getAxis('method') == null)
                        {
                            throw new IllegalArgumentException('Cannot point reference axis transformer to non-existing axis (' +
                                    transformMethodName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                    ', target: ' + transformApp + ' / ' + transformVersion + ' / ' + transformCubeName + '.' + transformMethodName)
                        }
                    }

                    ncube.clearSha1()   // changing meta properties does not clear SHA-1 for recalculation.
                    persister.updateCube(axisRef.srcAppId, ncube, getUserId())
                }
            }
        }
    }

    // ----------------------------------------- Resource APIs ---------------------------------------------------------
    static String getResourceAsString(String name) throws Exception
    {
        URL url = NCubeManager.class.getResource('/' + name)
        Path resPath = Paths.get(url.toURI())
        return new String(Files.readAllBytes(resPath), "UTF-8")
    }

    protected static NCube getNCubeFromResource(String name)
    {
        return getNCubeFromResource(ApplicationID.testAppId, name)
    }

    static NCube getNCubeFromResource(ApplicationID id, String name)
    {
        try
        {
            String json = getResourceAsString(name)
            NCube ncube = NCube.fromSimpleJson(json)
            ncube.applicationID = id
            ncube.sha1()
            addCube(id, ncube)
            return ncube
        }
        catch (NullPointerException e)
        {
            throw new IllegalArgumentException('Could not find the file [n-cube]: ' + name + ', app: ' + id, e)
        }
        catch (Exception e)
        {
            if (e instanceof RuntimeException)
            {
                throw (RuntimeException)e
            }
            throw new RuntimeException('Failed to load cube from resource: ' + name, e)
        }
    }

    /**
     * Still used in getNCubesFromResource
     */
    private static Object[] getJsonObjectFromResource(String name) throws IOException
    {
        JsonReader reader = null
        try
        {
            URL url = NCubeManager.class.getResource('/' + name)
            File jsonFile = new File(url.file)
            InputStream input = new BufferedInputStream(new FileInputStream(jsonFile))
            reader = new JsonReader(input, true)
            return (Object[]) reader.readObject()
        }
        finally
        {
            IOUtilities.close(reader)
        }
    }

    static List<NCube> getNCubesFromResource(String name)
    {
        String lastSuccessful = ''
        try
        {
            Object[] cubes = getJsonObjectFromResource(name)
            List<NCube> cubeList = new ArrayList<>(cubes.length)

            for (Object cube : cubes)
            {
                JsonObject ncube = (JsonObject) cube
                String json = JsonWriter.objectToJson(ncube)
                NCube nCube = NCube.fromSimpleJson(json)
                nCube.sha1()
                addCube(nCube.applicationID, nCube)
                lastSuccessful = nCube.name
                cubeList.add(nCube)
            }

            return cubeList
        }
        catch (Exception e)
        {
            String s = 'Failed to load cubes from resource: ' + name + ', last successful cube: ' + lastSuccessful
            LOG.warn(s)
            throw new RuntimeException(s, e)
        }
    }

    /**
     * Resolve the passed in String URL to a fully qualified URL object.  If the passed in String URL is relative
     * to a path in the sys.classpath, this method will perform (indirectly) the necessary HTTP HEAD requests to
     * determine which path it connects to.
     * @param url String url (relative or absolute)
     * @param input Map coordinate that the reuqested the URL (may include environment level settings that
     *              help sys.classpath select the correct ClassLoader.
     * @return URL fully qualified URL based on the passed in relative or absolute URL String.
     */
    static URL getActualUrl(ApplicationID appId, String url, Map input)
    {
        validateAppId(appId)
        if (StringUtilities.isEmpty(url))
        {
            throw new IllegalArgumentException('URL cannot be null or empty, attempting to resolve relative to absolute url for app: ' + appId)
        }
        String localUrl = url.toLowerCase()

        if (localUrl.startsWith('http:') || localUrl.startsWith('https:') || localUrl.startsWith('file:'))
        {   // Absolute URL
            try
            {
                return new URL(url)
            }
            catch (MalformedURLException e)
            {
                throw new IllegalArgumentException('URL is malformed: ' + url, e)
            }
        }
        else
        {
            URL actualUrl
            synchronized (url.intern())
            {
                URLClassLoader loader = getUrlClassLoader(appId, input)

                // Make URL absolute (uses URL roots added to NCubeManager)
                actualUrl = loader.getResource(url)
            }

            if (actualUrl == null)
            {
                String err = 'Unable to resolve URL, make sure appropriate resource URLs are added to the sys.classpath cube, URL: ' +
                        url + ', app: ' + appId
                throw new IllegalArgumentException(err)
            }
            return actualUrl
        }
    }

    // ---------------------------------------- Validation APIs --------------------------------------------------------
    protected static void validateAppId(ApplicationID appId)
    {
        if (appId == null)
        {
            throw new IllegalArgumentException('ApplicationID cannot be null')
        }
        appId.validate()
    }

    protected static void validateCube(NCube cube)
    {
        if (cube == null)
        {
            throw new IllegalArgumentException('NCube cannot be null')
        }
        NCube.validateCubeName(cube.name)
    }

    // ---------------------- Broadcast APIs for notifying other services in cluster of cache changes ------------------
    protected static void broadcast(ApplicationID appId)
    {
        // Write to 'system' tenant, 'NCE' app, version '0.0.0', SNAPSHOT, cube: sys.cache
        // Separate thread reads from this table every 1 second, for new commands, for
        // example, clear cache
        appId.toString()
    }

    // --------------------------------------- Permissions -------------------------------------------------------------

    /**
     * Assert that the requested permission is allowed.  Throw a SecurityException if not.
     */
    static boolean assertPermissions(ApplicationID appId, String resource, Action action = Action.READ)
    {
        if (checkPermissions(appId, resource, action))
        {
            return true
        }
        throw new SecurityException('Operation not performed.  You do not have ' + action.name() + ' permission to ' + resource + ', app: ' + appId)
    }

    protected static boolean assertNotLockBlocked(ApplicationID appId)
    {
        String lockedBy = getAppLockedBy(appId)
        if (lockedBy == null || lockedBy == getUserId())
        {
            return true
        }
        throw new SecurityException('Application is not locked by you, app: ' + appId)
    }

    private static void assertLockedByMe(ApplicationID appId)
    {
        final ApplicationID bootAppId = getBootAppId(appId)
        final NCube sysLockCube = getCubeInternal(bootAppId, SYS_LOCK)
        if (sysLockCube == null)
        {   // If there is no sys.lock cube, then no permissions / locking being used.
            if (isJUnitTest())
            {
                return
            }
            throw new SecurityException('Application is not locked by you, no sys.lock n-cube exists in app: ' + appId)
        }

        final String lockOwner = getAppLockedBy(bootAppId)
        if (getUserId() == lockOwner)
        {
            return
        }
        throw new SecurityException('Application is not locked by you, app: ' + appId)
    }

    private static ApplicationID getBootAppId(ApplicationID appId)
    {
        return new ApplicationID(appId.tenant, appId.app, '0.0.0', ReleaseStatus.SNAPSHOT.name(), ApplicationID.HEAD)
    }

    private static String getPermissionCacheKey(ApplicationID appId, String resource, Action action)
    {
        String key = getUserId() + '/' + appId.cacheKey(null) + '/' + resource + '/' + action
        return EncryptionUtilities.calculateSHA1Hash(key.bytes)
    }

    /**
     * Verify whether the action can be performed against the resource (typically cube name).
     * @param appId ApplicationID containing the n-cube being checked.
     * @param resource String cubeName or cubeName with wildcards('*' or '?') or cubeName / axisName (with wildcards).
     * @param action Action To be attempted.
     * @return boolean true if allowed, false if not.  If the permissions cubes restricting access have not yet been
     * added to the same App, then all access is granted.
     */
    static boolean checkPermissions(ApplicationID appId, String resource, Action action)
    {
        String key = getPermissionCacheKey(appId, resource, action)
        Boolean allowed = checkPermissionCache(key)
        if (allowed instanceof Boolean)
        {
            return allowed
        }
        long now = System.currentTimeMillis()

        if (Action.READ == action && SYS_LOCK.equalsIgnoreCase(resource))
        {
            permCache[key] = now
            return true
        }

        ApplicationID bootVersion = getBootAppId(appId)
        NCube permCube = getCubeInternal(bootVersion, SYS_PERMISSIONS)
        if (permCube == null)
        {   // Allow everything if no permissions are set up.
            permCache[key] = now
            return true
        }

        NCube userToRole = getCubeInternal(bootVersion, SYS_USERGROUPS)
        if (userToRole == null)
        {   // Allow everything if no user roles are set up.
            permCache[key] = now
            return true
        }

        // Step 1: Get user's roles
        Set<String> roles = getRolesForUser(userToRole)

        if (!roles.contains(ROLE_ADMIN) && CUBE_MUTATE_ACTIONS.contains(action))
        {   // If user is not an admin, check branch permissions.
            NCube branchPermCube = getCubeInternal(bootVersion.asBranch(appId.branch), SYS_BRANCH_PERMISSIONS)
            if (branchPermCube != null && !checkBranchPermission(branchPermCube, resource))
            {
                permCache[key] = -now
                return false
            }
        }

        // Step 2: Make sure one of the user's roles allows access
        final String actionName = action.lower()
        for (String role : roles)
        {
            if (checkResourcePermission(permCube, role, resource, actionName))
            {
                permCache[key] = now
                return true
            }
        }

        permCache[key] = -now
        return false
    }

    private static Boolean checkPermissionCache(String key)
    {
        Long allowed = permCache.get(key)

        if (allowed instanceof Long)
        {
            long now = System.currentTimeMillis()
            long elapsed = now - Math.abs(allowed.longValue())

            if (elapsed < PERMISSION_CACHE_THRESHOLD)
            {   // Less than a time threshold from last check, re-use last answer
                boolean allow = allowed >= 0
                permCache[key] = allow ? now : -now
                return allow
            }
        }
        return null
    }

    /**
     * Faster permissions check that should be used when filtering a list of n-cubes.  Before calling this
     * API, call getPermInfo(AppId) to get the 'permInfo' Map to be used in this API.
     */
    static boolean fastCheckPermissions(ApplicationID appId, String resource, Action action, Map permInfo)
    {
        String key = getPermissionCacheKey(appId, resource, action)
        Boolean allowed = checkPermissionCache(key)
        if (allowed instanceof Boolean)
        {
            return allowed
        }
        long now = System.currentTimeMillis()

        if (Action.READ == action && SYS_LOCK.equalsIgnoreCase(resource))
        {
            permCache[key] = now
            return true
        }

        Set<String> roles = permInfo.roles as Set
        if (!roles.contains(ROLE_ADMIN) && CUBE_MUTATE_ACTIONS.contains(action))
        {   // If user is not an admin, check branch permissions.
            NCube branchPermCube = (NCube)permInfo.branchPermCube
            if (branchPermCube != null && !checkBranchPermission(branchPermCube, resource))
            {
                permCache[key] = -now
                return false
            }
        }

        // Step 2: Make sure one of the user's roles allows access
        final String actionName = action.lower()
        NCube permCube = permInfo.permCube as NCube
        for (String role : roles)
        {
            if (checkResourcePermission(permCube, role, resource, actionName))
            {
                permCache[key] = now
                return true
            }
        }
        permCache[key] = -now
        return false
    }

    private static Map getPermInfo(ApplicationID appId)
    {
        Map<String, Object> info = [skipPermCheck:false] as Map
        ApplicationID bootVersion = getBootAppId(appId)
        info.bootVersion = bootVersion
        NCube permCube = getCubeInternal(bootVersion, SYS_PERMISSIONS)
        if (permCube == null)
        {   // Allow everything if no permissions are set up.
            info.skipPermCheck = true
        }
        info.permCube = permCube

        NCube userToRole = getCubeInternal(bootVersion, SYS_USERGROUPS)
        if (userToRole == null)
        {   // Allow everything if no user roles are set up.
            info.skipPermCheck = true
        }
        else
        {
            info.roles = getRolesForUser(userToRole)
        }

        info.branch000 = bootVersion.asBranch(appId.branch)
        info.branchPermCube = getCubeInternal((ApplicationID)info.branch000, SYS_BRANCH_PERMISSIONS)
        return info
    }

    private static boolean checkBranchPermission(NCube branchPermissions, String resource)
    {
        final List<Column> resourceColumns = getResourcesToMatch(branchPermissions, resource)
        final String userId = getUserId()
        final Column column = resourceColumns.find { branchPermissions.getCell([resource: it.value, user: userId])}
        return column != null
    }

    private static boolean checkResourcePermission(NCube resourcePermissions, String role, String resource, String action)
    {
        final List<Column> resourceColumns = getResourcesToMatch(resourcePermissions, resource)
        final Column column = resourceColumns.find {resourcePermissions.getCell([(AXIS_ROLE): role, resource: it.value, action: action]) }
        return column != null
    }

    private static Set<String> getRolesForUser(NCube userGroups)
    {
        Axis role = userGroups.getAxis(AXIS_ROLE)
        Set<String> groups = new HashSet()
        for (Column column : role.columns)
        {
            if (userGroups.getCell([(AXIS_ROLE): column.value, (AXIS_USER): getUserId()]))
            {
                groups.add(column.value as String)
            }
        }
        return groups
    }

    private static List<Column> getResourcesToMatch(NCube permCube, String resource)
    {
        List<Column> matches = []
        Axis resourcePermissionAxis = permCube.getAxis(AXIS_RESOURCE)
        if (resource != null)
        {
            String[] splitResource = resource.split('/')
            boolean shouldCheckAxis = splitResource.length > 1
            String resourceCube = splitResource[0]
            String resourceAxis = shouldCheckAxis ? splitResource[1] : null

            for (Column resourcePermissionColumn : resourcePermissionAxis.columnsWithoutDefault)
            {
                String columnResource = resourcePermissionColumn.value
                String[] curSplitResource = columnResource.split('/')
                boolean resourceIncludesAxis = curSplitResource.length > 1
                String curResourceCube = curSplitResource[0]
                String curResourceAxis = resourceIncludesAxis ? curSplitResource[1] : null
                boolean resourceMatchesCurrentResource = doStringsWithWildCardsMatch(resourceCube, curResourceCube)

                if ((shouldCheckAxis && resourceMatchesCurrentResource && doStringsWithWildCardsMatch(resourceAxis, curResourceAxis))
                        || (!shouldCheckAxis && !resourceIncludesAxis && resourceMatchesCurrentResource))
                {
                    matches << resourcePermissionColumn
                }
            }
        }
        if (matches.size() == 0)
        {
            matches.add(resourcePermissionAxis.defaultColumn)
        }
        return matches
    }

    private static boolean doStringsWithWildCardsMatch(String text, String pattern)
    {
        if (pattern == null)
        {
            return false
        }

        Pattern p = wildcards[pattern]
        if (p != null)
        {
            return p.matcher(text).matches()
        }

        String regexString = '(?i)' + StringUtilities.wildcardToRegexString(pattern)
        p = Pattern.compile(regexString)
        wildcards[pattern] = p
        return p.matcher(text).matches()
    }

    static boolean isAdmin(ApplicationID appId)
    {
        NCube userCube = getCubeInternal(getBootAppId(appId), SYS_USERGROUPS)
        if (userCube == null)
        {   // Allow everything if no permissions are set up.
            return true
        }
        return isUserInGroup(userCube, ROLE_ADMIN)
    }

    private static boolean isUserInGroup(NCube userCube, String groupName)
    {
        return userCube.getCell([(AXIS_ROLE): groupName, (AXIS_USER): null]) || userCube.getCell([(AXIS_ROLE): groupName, (AXIS_USER): getUserId()])
    }

    protected static void detectNewAppId(ApplicationID appId)
    {
        if (search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):false]).size() == 0)
        {
            addAppPermissionsCubes(appId)
            if (!appId.head)
            {
                addBranchPermissionsCube(appId)
            }
        }
    }

    private static void addBranchPermissionsCube(ApplicationID appId)
    {
        ApplicationID permAppId = appId.asVersion('0.0.0')
        if (getCubeInternal(permAppId, SYS_BRANCH_PERMISSIONS) != null)
        {
            return
        }

        String userId = getUserId()
        NCube branchPermCube = new NCube(SYS_BRANCH_PERMISSIONS)
        branchPermCube.applicationID = permAppId
        branchPermCube.defaultCellValue = false

        Axis resourceAxis = new Axis(AXIS_RESOURCE, AxisType.DISCRETE, AxisValueType.STRING, true)
        resourceAxis.addColumn(SYS_BRANCH_PERMISSIONS)
        branchPermCube.addAxis(resourceAxis)

        Axis userAxis = new Axis(AXIS_USER, AxisType.DISCRETE, AxisValueType.STRING, true)
        userAxis.addColumn(userId)
        branchPermCube.addAxis(userAxis)

        branchPermCube.setCell(true, [(AXIS_USER):userId, (AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS])
        branchPermCube.setCell(true, [(AXIS_USER):userId, (AXIS_RESOURCE):null])

        persister.updateCube(permAppId, branchPermCube, userId)
        VersionControl.updateBranch(permAppId)
    }

    private static void addAppPermissionsCubes(ApplicationID appId)
    {
        ApplicationID permAppId = getBootAppId(appId)
        addAppUserGroupsCube(permAppId)
        addAppPermissionsCube(permAppId)
        addSysLockingCube(permAppId)
    }

    private static void addSysLockingCube(ApplicationID appId)
    {
        if (getCubeInternal(appId, SYS_LOCK) != null)
        {
            return
        }

        NCube sysLockCube = new NCube(SYS_LOCK)
        sysLockCube.applicationID = appId
        sysLockCube.setMetaProperty(PROPERTY_CACHE, false)
        sysLockCube.addAxis(new Axis(AXIS_SYSTEM, AxisType.DISCRETE, AxisValueType.STRING, true))
        persister.updateCube(appId, sysLockCube, getUserId())
    }

    /**
     * Determine if the ApplicationID is locked.  This is an expensive call because it
     * always hits the database.  Use judiciously (obtain value before loops, etc.)
     */
    static String getAppLockedBy(ApplicationID appId)
    {
        NCube sysLockCube = getCubeInternal(getBootAppId(appId), SYS_LOCK)
        if (sysLockCube == null)
        {
            return null
        }
        return sysLockCube.getCell([(AXIS_SYSTEM):null])
    }

    /**
     * Lock the given appId so that no changes can be made to any cubes within it
     * @param appId ApplicationID to lock
     */
    static boolean lockApp(ApplicationID appId)
    {
        String userId = getUserId()
        ApplicationID bootAppId = getBootAppId(appId)

        String lockOwner = getAppLockedBy(appId)
        if (userId == lockOwner)
        {
            return false
        }
        if (lockOwner != null)
        {
            throw new SecurityException('Application ' + appId + ' already locked by ' + lockOwner)
        }

        NCube sysLockCube = getCubeInternal(bootAppId, SYS_LOCK)
        if (sysLockCube == null)
        {
            return false
        }
        sysLockCube.setCell(userId, [(AXIS_SYSTEM):null])
        persister.updateCube(bootAppId, sysLockCube, userId)
        return true
    }

    /**
     * Unlock the given appId so that changes can be made to any cubes within it
     * @param appId ApplicationID to unlock
     */
    static void unlockApp(ApplicationID appId)
    {
        ApplicationID bootAppId = getBootAppId(appId)
        NCube sysLockCube = getCubeInternal(bootAppId, SYS_LOCK)
        if (sysLockCube == null)
        {
            return
        }

        String userId = getUserId()
        String lockOwner = getAppLockedBy(appId)
        if (userId != lockOwner)
        {
            throw new SecurityException('Application ' + appId + ' locked by ' + lockOwner)
        }

        sysLockCube.removeCell([(AXIS_SYSTEM):null])
        persister.updateCube(bootAppId, sysLockCube, getUserId())
    }

    private static void addAppUserGroupsCube(ApplicationID appId)
    {
        if (getCubeInternal(appId, SYS_USERGROUPS) != null) {
            return
        }

        String userId = getUserId()
        NCube userGroupsCube = new NCube(SYS_USERGROUPS)
        userGroupsCube.applicationID = appId
        userGroupsCube.defaultCellValue = false

        Axis userAxis = new Axis(AXIS_USER, AxisType.DISCRETE, AxisValueType.STRING, true)
        userAxis.addColumn(userId)
        userGroupsCube.addAxis(userAxis)

        Axis roleAxis = new Axis(AXIS_ROLE, AxisType.DISCRETE, AxisValueType.STRING, false)
        roleAxis.addColumn(ROLE_ADMIN)
        roleAxis.addColumn(ROLE_READONLY)
        roleAxis.addColumn(ROLE_USER)
        userGroupsCube.addAxis(roleAxis)

        userGroupsCube.setCell(true, [(AXIS_USER):userId, (AXIS_ROLE):ROLE_ADMIN])
        userGroupsCube.setCell(true, [(AXIS_USER):userId, (AXIS_ROLE):ROLE_USER])
        userGroupsCube.setCell(true, [(AXIS_USER):null, (AXIS_ROLE):ROLE_USER])

        persister.updateCube(appId, userGroupsCube, userId)
    }

    private static void addAppPermissionsCube(ApplicationID appId)
    {
        if (getCubeInternal(appId, SYS_PERMISSIONS)) {
            return
        }

        NCube appPermCube = new NCube(SYS_PERMISSIONS)
        appPermCube.applicationID = appId
        appPermCube.defaultCellValue = false

        Axis resourceAxis = new Axis(AXIS_RESOURCE, AxisType.DISCRETE, AxisValueType.STRING, true)
        resourceAxis.addColumn(SYS_PERMISSIONS)
        resourceAxis.addColumn(SYS_USERGROUPS)
        resourceAxis.addColumn(SYS_BRANCH_PERMISSIONS)
        resourceAxis.addColumn(SYS_LOCK)
        appPermCube.addAxis(resourceAxis)

        Axis roleAxis = new Axis(AXIS_ROLE, AxisType.DISCRETE, AxisValueType.STRING, false)
        roleAxis.addColumn(ROLE_ADMIN)
        roleAxis.addColumn(ROLE_READONLY)
        roleAxis.addColumn(ROLE_USER)
        appPermCube.addAxis(roleAxis)

        Axis actionAxis = new Axis(AXIS_ACTION, AxisType.DISCRETE, AxisValueType.STRING, false)
        actionAxis.addColumn(Action.UPDATE.lower())
        actionAxis.addColumn(Action.READ.lower())
        actionAxis.addColumn(Action.RELEASE.lower())
        actionAxis.addColumn(Action.COMMIT.lower())
        appPermCube.addAxis(actionAxis)

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.COMMIT.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.COMMIT.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):Action.READ.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):Action.READ.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_LOCK, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_LOCK, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_LOCK, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.COMMIT.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.RELEASE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):Action.COMMIT.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):Action.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):Action.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):Action.COMMIT.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_READONLY, (AXIS_ACTION):Action.READ.lower()])

        persister.updateCube(appId, appPermCube, getUserId())
    }

    /**
     * Testing API (Cache validation)
     */
    static boolean isCubeCached(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        Map<String, Object> ncubes = getCacheForApp(appId)
        Object cachedItem = ncubes[cubeName.toLowerCase()]
        return cachedItem instanceof NCube
    }

    private static void cacheCube(ApplicationID appId, NCube ncube)
    {
        if (!ncube.metaProperties.containsKey(PROPERTY_CACHE) || Boolean.TRUE == ncube.getMetaProperty(PROPERTY_CACHE))
        {
            Map<String, Object> cache = getCacheForApp(appId)
            cache[ncube.name.toLowerCase()] = ncube
        }
    }

    protected static void removeCachedCube(ApplicationID appId, String cubeName)
    {
        if (StringUtilities.isEmpty(cubeName))
        {
            return
        }
        Map<String, Object> cache = getCacheForApp(appId)
        cache.remove(cubeName.toLowerCase())
        // TODO: broadcast(appId, cubeName) // cube level remove
    }

    /**
     * Set the user ID on the current thread
     * @param user String user Id
     */
    static void setUserId(String user)
    {
        userId.set(user?.trim())
    }

    /**
     * Retrieve the user ID from the current thread
     * @return String user ID of the user associated to the requesting thread
     */
    static String getUserId()
    {
        return userId.get()
    }

    /**
     * Add wild card symbol at beginning and at end of string if not already present.
     * Remove wild card symbol if only character present.
     * @return String
    */
    private static String handleWildCard(String value)
    {
        if (value)
        {
            if (!value.startsWith('*'))
            {
                value = '*' + value
            }
            if (!value.endsWith('*'))
            {
                value += '*'
            }
            if ('*' == value)
            {
                value = null
            }
        }
        return value
    }
}
