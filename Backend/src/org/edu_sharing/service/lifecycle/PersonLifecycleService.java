package org.edu_sharing.service.lifecycle;



import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.*;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.security.*;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.apache.log4j.Logger;
import org.edu_sharing.alfrescocontext.gate.AlfAppContextGate;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.edu_sharing.repository.server.MCAlfrescoAPIClient;
import org.edu_sharing.repository.server.tools.ApplicationInfoList;
import org.edu_sharing.repository.server.tools.VCardConverter;
import org.edu_sharing.repository.server.tools.cache.EduSharingRatingCache;
import org.edu_sharing.repository.server.tools.cache.RepositoryCache;
import org.edu_sharing.service.authentication.ScopeUserHomeService;
import org.edu_sharing.service.authentication.ScopeUserHomeServiceFactory;
import org.edu_sharing.service.collection.CollectionServiceFactory;
import org.edu_sharing.service.nodeservice.NodeServiceFactory;
import org.edu_sharing.service.nodeservice.NodeServiceHelper;
import org.edu_sharing.service.nodeservice.RecurseMode;
import org.edu_sharing.service.stream.StreamServiceFactory;
import org.edu_sharing.service.tracking.TrackingServiceFactory;
import org.springframework.context.ApplicationContext;


/**
 * Konzept LöschJob -> status todelete
 * 
 * Filter in personsearch (invite, workflow - non active)
 * 
 * validate session only active, 
 * webdav auth, share auth ....
 * 
 * on create person set personstatus to 'active'
 * 
 * job that sets active status for existing persons when edu-sharing property 'person_active_status' is set
 * 
 * user_home remove with/without cc -> with: move cc content to cc_user space (hirachical date folders structure)
 * 
 * 
 * all files in shared content will be deleted except OER(cc) content:
 * student, external, teacher trainee's
 * instance owner becomes owner
 * Collections -> delete?
 * 
 * all files in shared content stay and will be given to an Instanceowner 
 * Teacher, Staff
 * 
 * InviteHistory: delete or rename
 * 
 * filtered in invite dialogs
 * 
 * cause of owner (first/lastname) remove file from properties cache
 * 
 * 
 * @TODO find out instance owner
 * 
 * @TODO delete userhome keep CC
 * @TODO instanceowner instead of creator in gui (workspace column)
 * @TODO Collections (only level 0?)
 * @TODO shared content config ROLE_GROUP_REMOVE_SHARED delete cc vs not delete cc
 * @TODO function for changing owner of collection to another user (asking new user?)
 * @TODO check if Folders must be deleted in shared area, check if basket is necessary
 * 
 * Test
 * @TODO filter for TODELETE_STATUS already in search query
 * @TODO changing owner to instanceowner, remove old contributer
 * --> no username use firstName and lastName
 * 		Problems: user with same name, and marriage
 */
public class PersonLifecycleService {

	private static final String DELETED_PERSONS_FOLDER = "DELETED_PERSONS";
	private static final String USERHOME_ALL = "USERHOME";
	private static final String USERHOME_FILES = "USERHOME_FILES";
	private static final String USERHOME_FOLDERS = "USERHOME_FOLDERS";
	private static final String USERHOME_FILES_CC = "USERHOME_CC_FILES";
	private static final String SHARED_FILES = "SHARED_FILES";
	private static final String SHARED_FILES_CC = "SHARED_CC_FILES";
	public static String ROLE_STUDENT = "student";
	public static String ROLE_EXTERNAL = "external";
	public static String ROLE_TEACHER_TRAINEES = "teacher_trainees";
	public String[] ROLE_GROUP_REMOVE_SHARED = {ROLE_STUDENT,ROLE_EXTERNAL,ROLE_TEACHER_TRAINEES};
	
	public static String ROLE_TEACHER = "teacher";
	public static String ROLE_STAFF = "staff";
	public String[] ROLE_GROUP_KEEP_SHARED = {ROLE_TEACHER,ROLE_STAFF};
	
	
	ApplicationContext applicationContext = AlfAppContextGate.getApplicationContext();
	ServiceRegistry serviceRegistry = (ServiceRegistry) applicationContext.getBean(ServiceRegistry.SERVICE_REGISTRY);
	
	SearchService searchService = serviceRegistry.getSearchService();
	
	NodeService nodeService = serviceRegistry.getNodeService();
	
	PersonService personService = serviceRegistry.getPersonService();
	
	AuthorityService authorityService = serviceRegistry.getAuthorityService();
	
	PermissionService permissionService = serviceRegistry.getPermissionService();
	
	OwnableService ownableService = serviceRegistry.getOwnableService();

	TransactionService transactionService = serviceRegistry.getTransactionService();

	BehaviourFilter policyBehaviourFilter = (BehaviourFilter)applicationContext.getBean("policyBehaviourFilter");


	int maxItems = 20;

	public enum PersonStatus{
		active,
		blocked,
		todelete
	}

	Logger logger = Logger.getLogger(PersonLifecycleService.class);
	
	//public static String ROLE_

	/*
	private void deletePersons(int skipCount) {
		SearchParameters sp = new SearchParameters();
		sp.setQuery("TYPE:\"cm:person\" AND @cm\\:espersonstatus:" + PersonStatus.todelete.name());
		sp.setSkipCount(skipCount);
		sp.setMaxItems(maxItems);
		ResultSet rs = searchService.query(sp);
		for(NodeRef nodeRef : rs.getNodeRefs()) {
			deletePerson(nodeRef, options);
		}
		if(rs.hasMore()) {
			deletePersons(skipCount + maxItems);
		}
	}
    */
	
	public void deletePersons(List<String> usernames,PersonDeleteOptions options) {
		for(String user : usernames) {
			NodeRef personNodeRef = personService.getPerson(user);
			deletePerson(personNodeRef,options);
		}
	}
	
	private void deletePerson(NodeRef personNodeRef, PersonDeleteOptions options) {
		String status = (String)nodeService.getProperty(personNodeRef, 
				QName.createQName(CCConstants.CM_PROP_PERSON_ESPERSONSTATUS));
		String role = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_EDU_SCHOOL_PRIMARY_AFFILIATION));
		String userName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
		if(status != null && PersonStatus.todelete.name().equals(status)) {
			if(hasAssigning(options)){
				if(options.receiver==null || options.receiver.isEmpty() || options.receiverGroup==null || options.receiverGroup.isEmpty()) {
					throw new IllegalArgumentException("Some options set to assign, but no user + org was specified for assigning");
				}
				if(personService.getPersonOrNull(options.receiver)==null){
					throw new IllegalArgumentException("The given receiver is not a valid user authority");
				}
			}
			//shared files are "mounted" in the user home, so always process them first!
			//handleSharedFiles(personNodeRef,options); -> is now done via foreignFiles

			List<NodeRef> homeFiles = handleHomeHolder(personNodeRef, options, null);
			handleHomeHolder(personNodeRef,options,CCConstants.CCM_VALUE_SCOPE_SAFE);
			handleForeignFiles(personNodeRef,homeFiles,options);
			handleCollections(personNodeRef,options);

			handleStream(personNodeRef,options);
			if(options.comments.delete){
				deleteAllOfType(personNodeRef, CCConstants.CCM_TYPE_COMMENT);
			}
			if(options.ratings.delete) {
				List<NodeRef> ratings = getAllNodeRefs(userName, CCConstants.CCM_TYPE_RATING);
				ratings.forEach((ref)-> EduSharingRatingCache.delete(nodeService.getPrimaryParent(ref).getParentRef()));
				deleteAllRefs(ratings);
			}
			if(options.collectionFeedback.delete){
				deleteAllOfType(personNodeRef, CCConstants.CCM_TYPE_COLLECTION_FEEDBACK);
			}
			handleStatistics(personNodeRef,options);

			logger.info("deleting person");
			nodeService.addAspect(personNodeRef, ContentModel.ASPECT_TEMPORARY, null);
			personService.deletePerson(personNodeRef,true);
		}
		else{
			throw new IllegalArgumentException("Person "+userName+" is not marked for deletion. Cancelling");
		}
	}
	public List<NodeRef> getAllNodeRefs(String username, String type){
		SearchParameters params=new SearchParameters();
		params.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
		// will use the database
		params.setLanguage(SearchService.LANGUAGE_CMIS_ALFRESCO);
		params.setMaxItems(Integer.MAX_VALUE);
		String query="SELECT cmis:name FROM "+CCConstants.getValidLocalName(type)+" WHERE cmis:createdBy = '"+ username + "'";
		params.setQuery(query);
		ResultSet result = searchService.query(params);
		logger.info(query+": "+result.getNodeRefs().size());
		return result.getNodeRefs();
	}

	private void handleStatistics(NodeRef personNodeRef, PersonDeleteOptions options) {
		if(options.statistics.delete){
			String username = (String)nodeService.getProperty(personNodeRef,
					QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
			try {
				TrackingServiceFactory.getTrackingService().deleteUserData(username);
				logger.info("removed tracking/statistics data");
			}catch(Throwable t){
				throw new RuntimeException(t);
			}
		}
	}
	private List<NodeRef> deleteAllOfType(NodeRef personNodeRef,String type) {
			String username = (String)nodeService.getProperty(personNodeRef,
					QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
			List<NodeRef> refs = getAllNodeRefs(username,type);
			logger.info("Deleting all files of type "+type);
			deleteAllRefs(refs);
			return refs;
	}

	private void handleStream(NodeRef personNodeRef, PersonDeleteOptions options) {
		if(options.stream.delete) {
			String username = (String)nodeService.getProperty(personNodeRef,
					QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
			StreamServiceFactory.getStreamService().deleteEntriesByAuthority(username);
		}
	}

	private boolean hasAssigning(PersonDeleteOptions options) {
		return options.homeFolder.privateFiles.equals(PersonDeleteOptions.DeleteMode.assign) ||
				options.homeFolder.ccFiles.equals(PersonDeleteOptions.DeleteMode.assign) ||
				options.sharedFolders.privateFiles.equals(PersonDeleteOptions.DeleteMode.assign) ||
				options.sharedFolders.ccFiles.equals(PersonDeleteOptions.DeleteMode.assign) ||
				options.collections.privateCollections.equals(PersonDeleteOptions.DeleteMode.assign) ||
				options.collections.publicCollections.equals(PersonDeleteOptions.DeleteMode.assign);
	}

	private List<ChildAssociationRef> getAllSharedFolders(NodeRef personNodeRef, String scope) {
		String username = (String)nodeService.getProperty(personNodeRef,
				QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
		NodeRef homeFolder = null;
		if(scope == null) {
			homeFolder = getHomeFolder(personNodeRef);
		}else {
			ScopeUserHomeService scopeUserHomeService = ScopeUserHomeServiceFactory.getScopeUserHomeService();
			homeFolder = scopeUserHomeService.getUserHome(username, scope, false);
		}
		if(homeFolder != null) {
			List<ChildAssociationRef> childAssocs = nodeService.getChildAssocs(homeFolder);
			for(ChildAssociationRef childAssoc : childAssocs) {
				String mapType = (String)nodeService.getProperty(childAssoc.getChildRef(), QName.createQName(CCConstants.CCM_PROP_MAP_TYPE));
				if(CCConstants.CCM_VALUE_MAP_TYPE_EDUGROUP.equals(mapType)){
					return nodeService.getChildAssocs(childAssoc.getChildRef());
				}
			}
		}
		return null;
	}
	private void handleCollections(NodeRef personNodeRef, PersonDeleteOptions options) {
		if(options.collections.privateCollections.equals(PersonDeleteOptions.DeleteMode.none) &&
				options.collections.publicCollections.equals(PersonDeleteOptions.DeleteMode.none)){
			return;
		}
		String userName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
		NodeRef home = CollectionServiceFactory.getCollectionHome();
		List<NodeRef> allCollections = NodeServiceFactory.getLocalService().getChildrenRecursive(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE,home.getId(), null,RecurseMode.Folders);

		logger.info("Total collection data to check: "+allCollections.size());
		List<NodeRef> collections = allCollections.stream().filter((ref) -> ownableService.getOwner(ref).equals(userName)).collect(Collectors.toList());
		List<NodeRef> collectionsMaps = collections.stream().filter((ref) -> nodeService.hasAspect(ref, QName.createQName(CCConstants.CCM_ASPECT_COLLECTION))).collect(Collectors.toList());
		List<NodeRef> collectionsIos = collections.stream().filter((ref) -> nodeService.hasAspect(ref, QName.createQName(CCConstants.CCM_ASPECT_COLLECTION_IO_REFERENCE))).collect(Collectors.toList());
		List<NodeRef> collectionsPath = collections.stream().filter((ref) -> nodeService.getType(ref).equals(QName.createQName(CCConstants.CCM_TYPE_MAP)) && !nodeService.hasAspect(ref, QName.createQName(CCConstants.CCM_ASPECT_COLLECTION))).collect(Collectors.toList());

		// set all path stuff to admin
		collectionsPath.forEach((ref)->{
			setOwner(ref,userName, ApplicationInfoList.getHomeRepository().getUsername());
		});

		logger.info("Collections where "+userName+" is owner: "+collectionsMaps.size());
		logger.info("Collection Refs where "+userName+" is owner: "+collectionsIos.size());
		if(options.collections.privateCollections.equals(options.collections.publicCollections)){
			if(options.collections.privateCollections.equals(PersonDeleteOptions.DeleteMode.delete)) {
				// the maps may be not enough if the user contributed to foreign collections
				deleteAllRefs(collectionsIos);

				deleteAllRefs(collectionsMaps);
			}
			else if(options.collections.privateCollections.equals(PersonDeleteOptions.DeleteMode.assign)) {
				setOwnerAndPermissions(collectionsMaps, userName, options);
				//setOwnerAndPermissions(collectionsIos, userName, options);
				// io references should not support additional permissions (always inherit)
				collectionsIos.forEach((ref)->setOwner(ref,userName,options.receiver));
			}
			return;
		}
		throw new IllegalArgumentException("Currently collection deletion does only support the same modes for private and public");
	}

	/**
	 * All files that are from this user and not in the filesToIgnore list (which should be the home folder files)
	 * this can be files users contributed to other user homes he was invited to, or in shared folders
	 * @param personNodeRef
	 * @param filesToIgnore
	 * @param options
	 */
	private void handleForeignFiles(NodeRef personNodeRef, List<NodeRef> filesToIgnore, PersonDeleteOptions options) {
		String userName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
		// getting all ios (files) and maps (folders) where this user is the creator, but not any which should be ignored

		List<NodeRef> refsIO = getAllNodeRefs(userName, CCConstants.CCM_TYPE_IO).stream().
				filter((ref)->!filesToIgnore.contains(ref)).
				collect(Collectors.toList());
		List<NodeRef> refsMaps = getAllNodeRefs(userName, CCConstants.CCM_TYPE_MAP).stream().filter((ref)->!filesToIgnore.contains(ref)).collect(Collectors.toList());;

		// split the files by their license type
		List<NodeRef> filesPrivate = refsIO.stream().filter((ref) -> !hasCCLicense(ref)).collect(Collectors.toList());
		List<NodeRef> filesCC = refsIO.stream().filter((ref) -> hasCCLicense(ref)).collect(Collectors.toList());

		// handle cc files
		if(options.sharedFolders.ccFiles.equals(PersonDeleteOptions.DeleteMode.assign) && filesCC.size()>0){
			// switching the owner
			setOwnerAndPermissions(filesCC, userName, options);
			if(options.sharedFolders.move) {
				moveNodes(filesCC, getOrCreateTargetFolder(personNodeRef, options, SHARED_FILES_CC, null));
			}
		}else if(options.sharedFolders.ccFiles.equals(PersonDeleteOptions.DeleteMode.delete)){
			deleteAllRefs(filesCC);
		}
		// handle private files, that are not in the home dir
		if(options.sharedFolders.privateFiles.equals(PersonDeleteOptions.DeleteMode.assign) && filesPrivate.size()>0){
			setOwnerAndPermissions(filesPrivate, userName, options);
			if(options.sharedFolders.move) {
				moveNodes(filesPrivate, getOrCreateTargetFolder(personNodeRef, options, SHARED_FILES, null));
			}
		}else if(options.sharedFolders.privateFiles.equals(PersonDeleteOptions.DeleteMode.delete)){
			deleteAllRefs(filesPrivate);
		}
		// assign the new user to all folders
		if(options.sharedFolders.folders.equals(PersonDeleteOptions.FolderDeleteMode.assign)) {
			setOwnerAndPermissions(refsMaps, userName, options);
		}
	}
	/*
	private void handleSharedFiles(NodeRef personNodeRef, PersonDeleteOptions options) {
		if(options.sharedFolders.privateFiles.equals(PersonDeleteOptions.DeleteMode.none) &&
			options.sharedFolders.ccFiles.equals(PersonDeleteOptions.DeleteMode.none)){
			return;
		}
		String userName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));

		List<ChildAssociationRef> shared = getAllSharedFolders(personNodeRef, null);
		if(shared==null) {
			logger.info("User "+userName+" has no shared folders / orgs");

		}
		else{
			List<NodeRef> allFiles = new ArrayList<NodeRef>();
			shared.forEach(
					(childAssociationRef -> {
						allFiles.addAll(NodeServiceFactory.getLocalService().getChildrenRecursive(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, childAssociationRef.getChildRef().getId(), null, RecurseMode.Folders));
					})
			);
			logger.info("Total shared files to check: "+allFiles.size());
			List<NodeRef> files = allFiles.stream().filter((ref) -> ownableService.getOwner(ref).equals(userName)).collect(Collectors.toList());
			logger.info("Shared files where "+userName+" is owner: "+files.size());

			List<NodeRef> filesPrivate = files.stream().filter((ref) -> !hasCCLicense(ref)).collect(Collectors.toList());
			List<NodeRef> filesCC = files.stream().filter((ref) -> hasCCLicense(ref)).collect(Collectors.toList());
			if(options.sharedFolders.privateFiles.equals(PersonDeleteOptions.DeleteMode.assign)){
				if(options.sharedFolders.move) {
					NodeRef target = getOrCreateTargetFolder(personNodeRef, options, SHARED_FILES, null);
					setOwnerAndPermissions(filesPrivate, userName, options);
					moveNodes(filesPrivate, target);
				}
				else{
					setOwnerAndPermissions(filesPrivate, userName, options);
				}
			}
			if(options.sharedFolders.ccFiles.equals(PersonDeleteOptions.DeleteMode.assign)){
				if(options.sharedFolders.move) {
					NodeRef target = getOrCreateTargetFolder(personNodeRef, options, SHARED_FILES_CC, null);
					setOwnerAndPermissions(filesCC, userName, options);
					moveNodes(filesCC, target);
				}
				else{
					setOwnerAndPermissions(filesCC, userName, options);
				}
			}
			if(options.sharedFolders.privateFiles.equals(PersonDeleteOptions.DeleteMode.delete)){
				deleteAllRefs(filesPrivate);
			}
			if(options.sharedFolders.ccFiles.equals(PersonDeleteOptions.DeleteMode.delete)){
				deleteAllRefs(filesCC);
			}
		}
	}
	 */

	private List<NodeRef> handleHomeHolder(NodeRef personNodeRef, PersonDeleteOptions options, String scope) {
		NodeRef homeFolder;
		String userName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
		if(scope==null){
			homeFolder = getHomeFolder(personNodeRef);
		}
		else{
			ScopeUserHomeService scopeUserHomeService = ScopeUserHomeServiceFactory.getScopeUserHomeService();
			homeFolder = scopeUserHomeService.getUserHome((String) nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME)), scope, false);
			if(homeFolder==null){
				logger.info("Person "+userName+" does not have a scope folder for "+scope+", skipping it");
				return new ArrayList<>();
			}
		}
		// remove the dummy EDUGROUP folder first, it will lead to problems otherwise
		NodeRef child = NodeServiceFactory.getLocalService().getChild(homeFolder.getStoreRef(), homeFolder.getId(), CCConstants.CCM_TYPE_MAP, CCConstants.CCM_PROP_MAP_TYPE, CCConstants.CCM_VALUE_MAP_TYPE_EDUGROUP);
		if(child!=null){
			NodeServiceFactory.getLocalService().removeNode(child.getId(),null,false);
			logger.info("Deleting the EDUGROUP folder of "+userName);
		}
		List<NodeRef> childrens = NodeServiceFactory.getLocalService().getChildrenRecursive(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, homeFolder.getId(), null,RecurseMode.Folders);
		if(options.homeFolder.folders.equals(PersonDeleteOptions.FolderDeleteMode.assign)
				&& options.homeFolder.privateFiles.equals(PersonDeleteOptions.DeleteMode.assign)
				&& options.homeFolder.ccFiles.equals(PersonDeleteOptions.DeleteMode.assign)){
			if(options.homeFolder.keepFolderStructure){
				// @TODO: we set explicit permissions on all sub items because if a folder has inherit=false, then the org will not propagate
				childrens.add(homeFolder);
				setOwnerAndPermissions(childrens,userName,options);

				NodeRef deletedRef = getOrCreateTargetFolder(personNodeRef,options,USERHOME_ALL,scope);
				nodeService.moveNode(homeFolder,deletedRef,
						ContentModel.ASSOC_CONTAINS,
						QName.createQName(CCConstants.NAMESPACE_CCM, (String) nodeService.getProperty(homeFolder, QName.createQName(CCConstants.CM_NAME))));
				return childrens;
			}
		}
		if(options.homeFolder.keepFolderStructure){
			throw new IllegalArgumentException("keepFolderStructure is only allowed when all modes are set to assign");
		}
		else{
			List<NodeRef> folders = childrens.stream().filter((ref)->nodeService.getType(ref).equals(QName.createQName(CCConstants.CCM_TYPE_MAP))).collect(Collectors.toList());
			List<NodeRef> privateFiles =  childrens.stream().filter((ref)->nodeService.getType(ref).equals(QName.createQName(CCConstants.CCM_TYPE_IO)) && !hasCCLicense(ref)).collect(Collectors.toList());
			List<NodeRef> ccFiles = childrens.stream().filter((ref)->nodeService.getType(ref).equals(QName.createQName(CCConstants.CCM_TYPE_IO)) && hasCCLicense(ref)).collect(Collectors.toList());

			logger.info("Managing private files for "+userName);
			// handle private files
			if(options.homeFolder.privateFiles.equals(PersonDeleteOptions.DeleteMode.assign) && privateFiles.size()>0){
				NodeRef privateTarget = getOrCreateTargetFolder(personNodeRef, options,USERHOME_FILES,scope);
				setOwnerAndPermissions(privateFiles,userName,options);
				moveNodes(privateFiles,privateTarget);
			}else if(options.homeFolder.privateFiles.equals(PersonDeleteOptions.DeleteMode.delete)){
				deleteAllRefs(privateFiles);
			}
			logger.info("Managing cc files for "+userName);
			// handle cc files
			if(options.homeFolder.ccFiles.equals(PersonDeleteOptions.DeleteMode.assign) && ccFiles.size()>0){
				NodeRef ccTarget = getOrCreateTargetFolder(personNodeRef, options,USERHOME_FILES_CC,scope);
				setOwnerAndPermissions(ccFiles,userName,options);
				moveNodes(ccFiles,ccTarget);
			}else if(options.homeFolder.ccFiles.equals(PersonDeleteOptions.DeleteMode.delete)){
				deleteAllRefs(ccFiles);
			}
			logger.info("Managing folders for "+userName);
			// handle folders
			if(folders.size()>0) {
				NodeRef foldersTarget = getOrCreateTargetFolder(personNodeRef, options, USERHOME_FOLDERS, scope);
				if (options.homeFolder.folders.equals(PersonDeleteOptions.FolderDeleteMode.assign)) {
					setOwnerAndPermissions(folders, userName, options);
				}
				moveNodes(folders, foldersTarget);
			}
		}
		NodeServiceFactory.getLocalService().removeNode(homeFolder.getId(),null,false);
		return childrens;
	}

	private void moveNodes(List<NodeRef> refs, NodeRef targetRef) {
		List<QName> skipAspects = Arrays.asList(
				QName.createQName(CCConstants.CCM_ASPECT_IO_CHILDOBJECT),
				QName.createQName(CCConstants.CCM_ASPECT_COLLECTION),
				QName.createQName(CCConstants.CCM_ASPECT_COLLECTION_IO_REFERENCE)
		);

		refs.forEach((ref)-> {
			Set<QName> aspects = nodeService.getAspects(ref);
			List<QName> filtered = skipAspects.stream().filter((aspect) -> aspects.contains(aspect)).collect(Collectors.toList());
			if(filtered.size()>0){
				logger.info("will not move io since it contains an skip aspect "+filtered.get(0)+": "+ref);
				return;
			}
			RetryingTransactionHelper rth = transactionService.getRetryingTransactionHelper();
			AtomicBoolean rename= new AtomicBoolean(false);
			rth.doInTransaction((RetryingTransactionHelper.RetryingTransactionCallback<Void>) () -> {
				policyBehaviourFilter.disableBehaviour(ref);
				try {
					nodeService.moveNode(ref, targetRef,
							ContentModel.ASSOC_CONTAINS,
							QName.createQName(CCConstants.NAMESPACE_CCM, (String) nodeService.getProperty(ref, QName.createQName(CCConstants.CM_NAME))));
				} catch (DuplicateChildNodeNameException e) {
					rename.set(true);
				}
				policyBehaviourFilter.enableBehaviour(ref);
				return null;
			});
			if(rename.get()){
				rth.doInTransaction((RetryingTransactionHelper.RetryingTransactionCallback<Void>) () -> {
					policyBehaviourFilter.disableBehaviour(ref);
					try {
						String newName = nodeService.getProperty(ref, QName.createQName(CCConstants.CM_NAME)) + "_" + ref.getId();
						nodeService.setProperty(ref, QName.createQName(CCConstants.CM_NAME), newName);
						nodeService.moveNode(ref, targetRef,
								ContentModel.ASSOC_CONTAINS,
								QName.createQName(CCConstants.NAMESPACE_CCM, newName));
					} catch (DuplicateChildNodeNameException e) {
						rename.set(true);
					}
					policyBehaviourFilter.enableBehaviour(ref);
					return null;
				});
			}
		});
	}

	/**
	 * create the new folder where all deleted data should remain
	 * this folder will be placed in the receiverGroup (org) shared folder
	 * @param personNodeRef
	 * @param options
	 * @return
	 */
	private NodeRef getOrCreateTargetFolder(NodeRef personNodeRef, PersonDeleteOptions options,String subfolder,String scope) {
		String username= (String) nodeService.getProperty(personNodeRef,QName.createQName(CCConstants.CM_PROP_PERSON_USERNAME));
		NodeRef deleted = getDeletedFolderForOrg(options.receiverGroup, scope);
		String userFolder=NodeServiceFactory.getLocalService().findNodeByName(deleted.getId(),username);
		if(userFolder==null){
			HashMap<String, Object> props=new HashMap<>();
			props.put(CCConstants.CM_NAME,username);
			userFolder=NodeServiceFactory.getLocalService().createNodeBasic(deleted.getId(), CCConstants.CCM_TYPE_MAP, props);
		}
		if(subfolder==null)
			return new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE,userFolder);

		HashMap<String, Object> props=new HashMap<>();
		props.put(CCConstants.CM_NAME,subfolder);
		return new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE,NodeServiceFactory.getLocalService().createNodeBasic(userFolder, CCConstants.CCM_TYPE_MAP, props));

	}



	private NodeRef getDeletedFolderForOrg(String orgName, String scope) {
		NodeRef orgRef = authorityService.getAuthorityNodeRef(scope==null ? orgName : orgName+"_safe");
		if(orgRef == null){
			throw new IllegalArgumentException("Given org "+orgName+" could not be found");
		}
		NodeRef orgHome = (NodeRef) nodeService.getProperty(orgRef,
				QName.createQName(CCConstants.CCM_PROP_EDUGROUP_EDU_HOMEDIR));

		String deletedHome= NodeServiceFactory.getLocalService().findNodeByName(orgHome.getId(),DELETED_PERSONS_FOLDER);
		if(deletedHome==null) {
			HashMap<String, Object> props=new HashMap<>();
			props.put(CCConstants.CM_NAME,DELETED_PERSONS_FOLDER);
			deletedHome=NodeServiceFactory.getLocalService().createNodeBasic(orgHome.getId(), CCConstants.CCM_TYPE_MAP, props);
		}
		return new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE,deletedHome);
	}

	private void setOwnerAndPermissions(List<NodeRef> children, String userName, PersonDeleteOptions options) {
		RetryingTransactionHelper rth = transactionService.getRetryingTransactionHelper();
		rth.doInTransaction((RetryingTransactionHelper.RetryingTransactionCallback<Void>) () -> {
			children.forEach((ref) -> {
				setOwner(ref, userName, options.receiver);
				policyBehaviourFilter.disableBehaviour(ref);
				permissionService.setPermission(ref, options.receiverGroup, CCConstants.PERMISSION_COORDINATOR, true);
				policyBehaviourFilter.enableBehaviour(ref);
				logger.debug("setOwnerAndPermissions for " + ref);
			});
			return null;
		});
	}

	private void deleteAllRefs(List<NodeRef> refs) {
		refs.forEach((ref)->NodeServiceFactory.getLocalService().removeNode(ref.getId(),null,false));
	}


	private boolean hasCCLicense(NodeRef ref) {
		String license = NodeServiceHelper.getProperty(ref, CCConstants.CCM_PROP_IO_COMMONLICENSE_KEY);
		return CCConstants.getAllCCLicenseKeys().contains(license);
	}
	
	private void setOwner(NodeRef nodeRef, String oldOwner, String newOwner) {
		RetryingTransactionHelper rth = transactionService.getRetryingTransactionHelper();
		rth.doInTransaction((RetryingTransactionHelper.RetryingTransactionCallback<Void>) () -> {
			policyBehaviourFilter.disableBehaviour(nodeRef);
			ownableService.setOwner(nodeRef, newOwner);
			if (oldOwner.equals(nodeService.getProperty(nodeRef, QName.createQName(CCConstants.CM_PROP_C_CREATOR)))) {
				nodeService.setProperty(nodeRef, QName.createQName(CCConstants.CM_PROP_C_CREATOR), newOwner);
			}
			if (oldOwner.equals(nodeService.getProperty(nodeRef, QName.createQName(CCConstants.CM_PROP_C_MODIFIER)))) {
				nodeService.setProperty(nodeRef, QName.createQName(CCConstants.CM_PROP_C_MODIFIER), newOwner);
			}
			policyBehaviourFilter.enableBehaviour(nodeRef);
			return null;
		});
		new RepositoryCache().remove(nodeRef.getId());
	}
	
	private void deleteNode(NodeRef nodeRef) {
		/**
		 * remove without archiving
		 */
		nodeService.addAspect(nodeRef, ContentModel.ASPECT_TEMPORARY, null);
		nodeService.deleteNode(nodeRef);
	}
	
	public void removeContributer(NodeRef nodeRef, String username){
		NodeRef personNodeRef = personService.getPerson(username);
		String firstName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_FIRSTNAME));
		String lastName = (String)nodeService.getProperty(personNodeRef, QName.createQName(CCConstants.CM_PROP_PERSON_LASTNAME));
		
		QName qnameAuthor = QName.createQName(CCConstants.CCM_PROP_IO_REPL_LIFECYCLECONTRIBUTER_AUTHOR);
		QName qnameMetadata = QName.createQName(CCConstants.CCM_PROP_IO_REPL_METADATACONTRIBUTER_CREATOR);
		List<String> contributerAuthor = (List<String>)nodeService.getProperty(nodeRef, qnameAuthor);
		List<String> contributerMetadata = (List<String>)nodeService.getProperty(nodeRef,qnameMetadata);
		for(String author : contributerAuthor) {
			if(contains(VCardConverter.vcardToHashMap(author),firstName,lastName) ) {
				contributerAuthor.remove(author);
			}
		}
		nodeService.setProperty(nodeRef, qnameAuthor, (ArrayList)contributerAuthor);
		
		for(String metadataContributer : contributerMetadata) {
			if(contains(VCardConverter.vcardToHashMap(metadataContributer),firstName,lastName) ) {
				contributerMetadata.remove(metadataContributer);
			}
		}
		nodeService.setProperty(nodeRef, qnameMetadata, (ArrayList)contributerMetadata);
		
	}
	
	private boolean contains(ArrayList<HashMap<String, Object>> vcardList, String firstName, String lastName) {
		
		if(vcardList != null && vcardList.size() > 0) {
			Map<String,Object> vcard = vcardList.iterator().next();
			String vcFirstName = (String)vcard.get(CCConstants.VCARD_GIVENNAME);
			String vcLastName = (String)vcard.get(CCConstants.VCARD_SURNAME);
			
			if(firstName.equals(vcFirstName) && lastName.equals(vcLastName)) {
				return true;
			}
		}
		
		return false;
	}
	
	public void deleteCollections(String userName) {

		SearchParameters sp = new SearchParameters();
		//sp.setQuery("ASPECT:\"ccm:collection\" AND @ccm\\:collectionlevel0:true AND OWNER:\""+userName+"\"");
		sp.setQuery("ASPECT:\"ccm:collection\" AND OWNER:\""+userName+"\"");
		sp.setSkipCount(0);
		sp.setMaxItems(-1);
		sp.addStore(MCAlfrescoAPIClient.storeRef);
		sp.setLanguage(SearchService.LANGUAGE_LUCENE);
		ResultSet rs = searchService.query(sp);
		for(NodeRef nodeRef : rs.getNodeRefs()) {
			String collection = (String)nodeService.getProperty(nodeRef, QName.createQName(CCConstants.CM_NAME));
			logger.info("deleteing collection:" + collection);
			nodeService.addAspect(nodeRef, ContentModel.ASPECT_TEMPORARY, null);
			nodeService.deleteNode(nodeRef);
		}
	}
	
	private void deleteScopeUserHome(String username, String scope, boolean keepCC) {
		ScopeUserHomeService scopeUserHomeService = ScopeUserHomeServiceFactory.getScopeUserHomeService();
		NodeRef homeFolder = scopeUserHomeService.getUserHome(username, scope, false);
		if(homeFolder == null) {
			return;
		}
		if(keepCC) {
			//@TODO
			logger.info("not implemented yet");
			return;
		}else {
			/**
			 * remove without archiving
			 */
			nodeService.addAspect(homeFolder, ContentModel.ASPECT_TEMPORARY, null);
			nodeService.deleteNode(homeFolder);
		}
	}
	
	private NodeRef getHomeFolder(NodeRef personNodeRef) {
		NodeRef homeFolder = (NodeRef)nodeService.getProperty(personNodeRef, 
				QName.createQName(CCConstants.CM_PROP_PERSON_HOME_FOLDER));
		return homeFolder;
	}

}
