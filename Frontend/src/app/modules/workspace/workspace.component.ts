import {Component, ElementRef, HostListener, ViewChild, OnInit, OnDestroy} from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Translation } from '../../core-ui-module/translation';
import {
    ClipboardObject,
    ConfigurationService,
    Connector,
    ConnectorList, DialogButton,
    EventListener,
    Filetype,
    FrameEventsService,
    IamUser,
    LoginResult,
    Node,
    NodeList,
    NodeRef,
    NodeVersions,
    NodeWrapper,
    RestCollectionService,
    RestConnectorService,
    RestConnectorsService,
    RestConstants,
    RestHelper,
    RestIamService,
    RestMdsService,
    RestNodeService,
    RestToolService,
    SessionStorageService,
    TemporaryStorageService,
    UIService,
    Version
} from '../../core-module/core.module';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { OptionItem } from '../../core-ui-module/option-item';
import { Toast } from '../../core-ui-module/toast';
import { UIAnimation } from '../../core-module/ui/ui-animation';
import { NodeHelper } from '../../core-ui-module/node-helper';
import { KeyEvents } from '../../core-module/ui/key-events';
import { Title } from '@angular/platform-browser';
import { UIHelper } from '../../core-ui-module/ui-helper';
import { trigger } from '@angular/animations';
import { UIConstants } from '../../core-module/ui/ui-constants';
import { ActionbarHelperService } from '../../common/services/actionbar-helper';
import { Helper } from '../../core-module/rest/helper';
import { DateHelper } from '../../core-ui-module/DateHelper';
import { CordovaService } from '../../common/services/cordova.service';
import { HttpClient } from '@angular/common/http';
import { MainNavComponent } from '../../common/ui/main-nav/main-nav.component';
import { MatMenuTrigger } from '@angular/material/menu';
import { GlobalContainerComponent } from '../../common/ui/global-container/global-container.component';
import {ActionbarComponent} from '../../common/ui/actionbar/actionbar.component';
import {BridgeService} from '../../core-bridge-module/bridge.service';
import {WorkspaceExplorerComponent} from './explorer/explorer.component';
import { CardService } from '../../core-ui-module/card.service';
import { Observable } from 'rxjs';

@Component({
    selector: 'workspace-main',
    templateUrl: 'workspace.component.html',
    styleUrls: ['workspace.component.scss'],
    animations: [
        trigger('fade', UIAnimation.fade()),
        trigger('fadeFast', UIAnimation.fade(UIAnimation.ANIMATION_TIME_FAST)),
        trigger('overlay', UIAnimation.openOverlay(UIAnimation.ANIMATION_TIME_FAST)),
        trigger('fromLeft', UIAnimation.fromLeft()),
        trigger('fromRight', UIAnimation.fromRight())
    ]
})
export class WorkspaceMainComponent implements EventListener, OnDestroy {
    @ViewChild('explorer') explorer: WorkspaceExplorerComponent;
    private static VALID_ROOTS = ['MY_FILES', 'SHARED_FILES', 'MY_SHARED_FILES', 'TO_ME_SHARED_FILES', 'WORKFLOW_RECEIVE', 'RECYCLE'];
    private static VALID_ROOTS_NODES = [RestConstants.USERHOME, '-shared_files-', '-my_shared_files-', '-to_me_shared_files-', '-workflow_receive-'];

    cardHasOpenModals$: Observable<boolean>;
    private isRootFolder: boolean;
    private homeDirectory: string;
    private sharedFolders: Node[] = [];
    private path: Node[] = [];
    private parameterNode: Node;
    private root = 'MY_FILES';

    private selection: Node[] = [];

    private showSelectRoot = false;
    createConnectorName: string;
    createConnectorType: Connector;

    public allowBinary = true;
    public globalProgress = false;
    public editNodeDeleteOnCancel = false;
    private createMds: string;
    private nodeDisplayedVersion: string;
    createAllowed: boolean;
    currentFolder: Node;
    private user: IamUser;
    public searchQuery: any;
    public isSafe = false;
    private isLoggedIn = false;
    public addNodesToCollection: Node[];
    public addNodesStream: Node[];
    public variantNode: Node;
    @ViewChild('mainNav') mainNavRef: MainNavComponent;
    private connectorList: Connector[];
    private currentNode: Node;
    public mainnav = true;
    private timeout: string;
    private timeIsValid = false;
    private viewToggle: OptionItem;
    private isAdmin = false;
    public isBlocked = false;
    private isGuest: boolean;
    private currentNodes: Node[];
    private appleCmd = false;
    private reurl: string;
    private mdsParentNode: Node;
    public showLtiTools = false;
    private oldParams: Params;
    private selectedNodeTree: string;
    public contributorNode: Node;
    public shareLinkNode: Node;
    private viewType = 0;
    private reurlDirectories: boolean;
    private reorderDialog: boolean;
    @HostListener('window:beforeunload', ['$event'])
    beforeunloadHandler(event: any) {
        if (this.isSafe) {
            this.connector.logoutSync();
        }
    }
    @HostListener('window:scroll', ['$event'])
    handleScroll(event: Event) {
        const scroll = (window.pageYOffset || document.documentElement.scrollTop);
        if (scroll > 0) {
            this.storage.set('workspace_scroll', scroll);
        }
    }
    @HostListener('document:keyup', ['$event'])
    handleKeyboardEventUp(event: KeyboardEvent) {
        if (event.keyCode === 91 || event.keyCode === 93) {
            this.appleCmd = false;
        }
    }
    @HostListener('document:keydown', ['$event'])
    handleKeyboardEvent(event: KeyboardEvent) {
        if (event.keyCode === 91 || event.keyCode === 93) {
            this.appleCmd = true;
            event.preventDefault();
            event.stopPropagation();
            return;
        }
        const clip = (this.storage.get('workspace_clipboard') as ClipboardObject);
        const fromInputField = KeyEvents.eventFromInputField(event);

        if (event.key === 'Escape') {
            if (this.createConnectorName != null) {
                this.createConnectorName = null;
            }
            else {
                return;
            }
            event.preventDefault();
            event.stopPropagation();
        }
    }
    onEvent(event: string, data: any): void {
        if (event === FrameEventsService.EVENT_REFRESH) {
            this.refresh();
        }
    }
    ngOnDestroy(): void {
        if(this.currentFolder) {
            this.storage.set(TemporaryStorageService.WORKSPACE_LAST_LOCATION, this.currentFolder.ref.id);
        }
    }
    constructor(
        private toast: Toast,
        private bridge: BridgeService,
        private route: ActivatedRoute,
        private router: Router,
        private http: HttpClient,
        private translate: TranslateService,
        private storage: TemporaryStorageService,
        private config: ConfigurationService,
        private connectors: RestConnectorsService,
        private actionbar: ActionbarHelperService,
        private collectionApi: RestCollectionService,
        private toolService: RestToolService,
        private session: SessionStorageService,
        private iam: RestIamService,
        private mds: RestMdsService,
        private node: RestNodeService,
        private ui: UIService,
        private title: Title,
        private event: FrameEventsService,
        private connector: RestConnectorService,
        private cordova: CordovaService,
        private card: CardService,
    ) {
        this.event.addListener(this);
        Translation.initialize(translate, this.config, this.session, this.route).subscribe(() => {
            UIHelper.setTitle('WORKSPACE.TITLE', title, translate, config);
            this.initialize();
        });
        this.connector.setRoute(this.route);
        this.globalProgress = true;
        this.cardHasOpenModals$ = card.hasOpenModals.delay(0);
    }

    private hideDialog(): void {
        this.toast.closeModalDialog();
    }
    // @ TODO: Move to create menu (probably)
    showCreateConnector(connector: Connector) {
        this.createConnectorName = '';
        this.createConnectorType = connector;
        this.iam.getUser().subscribe((user) => {
            if (user.person.quota.enabled && user.person.quota.sizeCurrent >= user.person.quota.sizeQuota) {
                this.toast.showModalDialog('CONNECTOR_QUOTA_REACHED_TITLE', 'CONNECTOR_QUOTA_REACHED_MESSAGE', DialogButton.getOk(() => {
                    this.toast.closeModalDialog();
                }), true);
                this.createConnectorName = null;
            }
        });
    }
    private editConnector(node: Node = null, type: Filetype = null, win: any = null, connectorType: Connector = null) {
        UIHelper.openConnector(this.connectors, this.iam, this.event, this.toast, this.getNodeList(node)[0], type, win, connectorType);
    }
    private handleDrop(event: any) {
        for (const s of event.source) {
            if (event.target.ref.id === s.ref.id || event.target.ref.id === s.parent.id) {
                this.toast.error(null, 'WORKSPACE.SOURCE_TARGET_IDENTICAL');
                return;
            }
        }
        if (!event.target.isDirectory) {
            this.toast.error(null, 'WORKSPACE.TARGET_NO_DIRECTORY');
            return;
        }
        if (event.event.altKey) {
            this.toast.error(null, 'WORKSPACE.FEATURE_NOT_IMPLEMENTED');
        }
        else if (event.type === 'copy') {
            this.copyNode(event.target, event.source);
        }
        else {
            this.moveNode(event.target, event.source);
        }
        /*
        this.dialogTitle="WORKSPACE.DRAG_DROP_TITLE";
        this.dialogCancelable=true;
        this.dialogMessage="WORKSPACE.DRAG_DROP_MESSAGE";
        this.dialogMessageParameters={source:event.source.name,target:event.target.name};
        this.dialogButtons=[
          new DialogButton("WORKSPACE.DRAG_DROP_COPY",DialogButton.TYPE_PRIMARY,()=>this.copyNode(event.target,event.source)),
          new DialogButton("WORKSPACE.DRAG_DROP_MOVE",DialogButton.TYPE_PRIMARY,()=>this.moveNode(event.target,event.source)),
        ]
        */
    }
    canDropBreadcrumbs = (event: any) => event.target.ref.id !== this.currentFolder.ref.id;
    private moveNode(target: Node, source: Node[], position = 0) {
        this.globalProgress = true;
        if (position >= source.length) {
            this.finishMoveCopy(target, source, false);
            this.globalProgress = false;
            return;
        }
        this.node.moveNode(target.ref.id, source[position].ref.id).subscribe((data: NodeWrapper) => {
            this.moveNode(target, source, position + 1);
        },
            (error: any) => {
                NodeHelper.handleNodeError(this.bridge, source[position].name, error);
                source.splice(position, 1);
                this.moveNode(target, source, position + 1);
            });
    }
    private copyNode(target: Node, source: Node[], position = 0) {
        this.globalProgress = true;
        if (position >= source.length) {
            this.finishMoveCopy(target, source, true);
            this.globalProgress = false;
            return;
        }
        this.node.copyNode(target.ref.id, source[position].ref.id).subscribe((data: NodeWrapper) => {
            this.copyNode(target, source, position + 1);
        },
            (error: any) => {
                NodeHelper.handleNodeError(this.bridge, source[position].name, error);
                source.splice(position, 1);
                this.copyNode(target, source, position + 1);
            });
    }
    private finishMoveCopy(target: Node, source: Node[], copy: boolean) {
        this.toast.closeModalDialog();
        const info: any = {
            to: target.name,
            count: source.length,
            mode: this.translate.instant('WORKSPACE.' + (copy ? 'PASTE_COPY' : 'PASTE_MOVE'))
        };
        if (source.length) {
            this.toast.toast('WORKSPACE.TOAST.PASTE_DRAG', info);
        }
        this.globalProgress = false;
        this.refresh();
    }
    private initialize() {
        this.route.params.subscribe((params: Params) => {
            this.isSafe = params.mode === 'safe';
            this.connector.isLoggedIn().subscribe((data: LoginResult) => {
                if (data.statusCode !== RestConstants.STATUS_CODE_OK) {
                    RestHelper.goToLogin(this.router, this.config);
                    return;
                }
                this.iam.getUser().subscribe((user: IamUser) => {
                    this.user = user;
                    this.loadFolders(user);

                    let valid = true;
                    this.isGuest = data.isGuest;
                    if (!data.isValidLogin || data.isGuest) {
                        valid = false;
                    }
                    this.isBlocked = !this.connector.hasToolPermissionInstant(RestConstants.TOOLPERMISSION_WORKSPACE);
                    this.isAdmin = data.isAdmin;
                    if (this.isSafe && data.currentScope !== RestConstants.SAFE_SCOPE) {
                        valid = false;
                    }
                    if (!this.isSafe && data.currentScope != null) {
                        valid = false;
                    }
                    if (!valid) {
                        this.goToLogin();
                        return;
                    }
                    this.connector.scope = this.isSafe ? RestConstants.SAFE_SCOPE : null;
                    this.isLoggedIn = true;
                    this.node.getHomeDirectory().subscribe((data: NodeRef) => {
                        this.globalProgress = false;
                        this.homeDirectory = data.id;
                        this.route.params.forEach((params: Params) => {
                            this.route.queryParams.subscribe((params: Params) => {

                                this.connectors.list().subscribe(() => {
                                    this.connectorList = this.connectors.getConnectors();
                                    if (params.connector) {
                                        this.showCreateConnector(this.connectorList.filter((c) => c.id === params.connector)[0]);
                                    }
                                });

                                let needsUpdate = false;
                                if (this.oldParams) {
                                    for (const key of Object.keys(this.oldParams).concat(Object.keys(params))) {
                                        if (params[key] !== this.oldParams[key] && key !== 'viewType') {
                                            needsUpdate = true;
                                        }
                                    }
                                }
                                else {
                                    needsUpdate = true;
                                }
                                this.oldParams = params;
                                if (params.viewType) {
                                    this.viewType = params.viewType;
                                }
                                if (params.root && WorkspaceMainComponent.VALID_ROOTS.indexOf(params.root) !== -1) {
                                    this.root = params.root;
                                }
                                else {
                                    this.root = 'MY_FILES';
                                }
                                if (params.reurl) {
                                    this.reurl = params.reurl;
                                }
                                this.reurlDirectories = params.applyDirectories === 'true';
                                this.createAllowed = this.root === 'MY_FILES';
                                this.mainnav = params.mainnav === 'false' ? false : true;

                                if (params.file) {
                                    this.node.getNodeMetadata(params.file).subscribe((data: NodeWrapper) => {
                                        this.setSelection([data.node]);
                                        this.parameterNode = data.node;
                                        this.mainNavRef.management.nodeSidebar = data.node;
                                    });
                                }

                                if (!needsUpdate) {
                                    return;
                                }
                                const lastLocation = this.storage.pop(TemporaryStorageService.WORKSPACE_LAST_LOCATION, null);
                                if(!params.id && lastLocation) {
                                    this.openDirectory(lastLocation);
                                } else {
                                    this.openDirectoryFromRoute(params);
                                }
                                if (params.showAlpha) {
                                    this.showAlpha();
                                }
                            });
                        });
                    });
                });
            });
        });
    }
    public resetWorkspace() {
        if (this.mainNavRef.management.nodeSidebar && this.parameterNode) {
            this.setSelection([this.parameterNode]);
        }
    }

    public doSearch(query: any) {
        const id = this.currentFolder ? this.currentFolder.ref.id :
            this.searchQuery && this.searchQuery.node ? this.searchQuery.node.ref.id : null;
        this.routeTo(this.root, id, query.query);
        if (!query.cleared) {
            this.ui.hideKeyboardIfMobile();
        }
    }
    private doSearchFromRoute(params: any, node: Node | any) {
        node = this.isRootFolder ? null : node;
        this.searchQuery = {
            query: params.query,
            node
        };
        if (node == null && this.root !== 'RECYCLE') {
            this.root = 'ALL_FILES';
        }
        this.createAllowed = false;
        this.path = [];
        this.selection = [];
    }
    private deleteDone() {
        this.closeMetadata();
        this.refresh();
    }

    private displayNode(event: Node) {
        const list = this.getNodeList(event);
        this.closeMetadata();
        if (list[0].isDirectory) {
            if(list[0].collection) {
                UIHelper.goToCollection(this.router,list[0]);
            } else {
                this.openDirectory(list[0].ref.id);
            }
        }
        else {
            /*
            this.nodeDisplayed = event;
            this.nodeDisplayedVersion = event.version;
            */
            this.currentNode = list[0];
            this.storage.set(TemporaryStorageService.NODE_RENDER_PARAMETER_LIST, this.currentNodes);
            this.storage.set(TemporaryStorageService.NODE_RENDER_PARAMETER_ORIGIN, 'workspace');
            this.router.navigate([UIConstants.ROUTER_PREFIX + 'render', list[0].ref.id, list[0].version ? list[0].version : '']);
        }
    }
    // returns either the passed node as list, or the current selection if the passed node is invalid (actionbar)
    private getNodeList(node: Node): Node[] {
        if (Array.isArray(node)) {
            return node;
        }
        let nodes = [node];
        if (node == null) {
            nodes = this.selection;
        }
        return nodes;
    }

    private loadFolders(user: IamUser) {
        for (const folder of user.person.sharedFolders) {
            this.node.getNodeMetadata(folder.id).subscribe((node: NodeWrapper) => this.sharedFolders.push(node.node));
        }
    }
    private setRoot(root: string) {
        this.root = root;
        this.searchQuery = null;
        this.routeTo(root, null, null);
    }
    private updateList(nodes: Node[]) {
        this.currentNodes = nodes;
    }

    private setSelection(nodes: Node[]) {
        this.selection = nodes;
        this.setFixMobileNav();
    }
    private setFixMobileNav() {
        this.mainNavRef.setFixMobileElements(this.selection && this.selection.length > 0);
    }
    private updateLicense() {
        this.closeMetadata();
    }
    private closeMetadata() {
        this.mainNavRef.management.closeSidebar();
    }
    private openDirectory(id: string) {
        this.routeTo(this.root, id);
    }
    searchGlobal(query: string) {
        this.routeTo(this.root, null, query);
    }
    private openDirectoryFromRoute(params: any) {
        let id = params.id;
        this.selection = [];
        this.closeMetadata();
        this.createAllowed = false;
        if (!id) {
            this.path = [];
            id = this.getRootFolderId();
            if (this.root === 'RECYCLE') {
                // GlobalContainerComponent.finishPreloading();
                // return;
            }
        }
        else {
            this.selectedNodeTree = id;
            this.node.getNodeParents(id).subscribe((data: NodeList) => {
                if (this.root === 'RECYCLE') {
                    this.path = [];
                }
                else {
                    this.path = data.nodes.reverse();
                }
                this.selectedNodeTree = null;
            }, (error: any) => {
                this.selectedNodeTree = null;
                this.path = [];
            });
        }
        this.currentFolder = null;
        this.allowBinary = true;
        const root = !id || WorkspaceMainComponent.VALID_ROOTS_NODES.indexOf(id) !== -1;
        if (!root) {
            this.isRootFolder = false;
            this.node.getNodeMetadata(id).subscribe((data: NodeWrapper) => {
                this.mds.getSet(data.node.metadataset ? data.node.metadataset : RestConstants.DEFAULT).subscribe((mds: any) => {
                    if (mds.create) {
                        this.allowBinary = !mds.create.onlyMetadata;
                        if (!this.allowBinary) {
                        }
                    }
                });
                this.updateNodeByParams(params, data.node);
                this.createAllowed = !this.searchQuery && NodeHelper.getNodesRight([data.node], RestConstants.ACCESS_ADD_CHILDREN);
                this.recoverScrollposition();
            }, (error: any) => {
                this.updateNodeByParams(params, { ref: { id } });
            });
        }
        else {
            this.isRootFolder = true;
            if (id === RestConstants.USERHOME) {
                this.createAllowed = true;
            }
            const node: Node|any = {
                ref: {
                    id
                },
                name: this.translate.instant('WORKSPACE.' + this.root)
            };
            if (this.root === 'MY_FILES') {
                node.access = [RestConstants.ACCESS_ADD_CHILDREN];
            }
            this.updateNodeByParams(params, node);
        }

    }
    private openNode(node: Node, useConnector = true) {
        if (NodeHelper.isSavedSearchObject(node)) {
            UIHelper.routeToSearchNode(this.router, null, node);
        }
        else if (RestToolService.isLtiObject(node)) {
            this.toolService.openLtiObject(node);
        }
        else if (useConnector && this.connectors.connectorSupportsEdit(node)) {
            this.editConnector(node);
        }
        else {
            this.displayNode(node);
        }
    }
    private openBreadcrumb(position: number) {
        /*this.path=this.path.slice(0,position+1);
        */
        this.searchQuery = null;
        let id = '';
        const length = this.path ? this.path.length : 0;
        if (position > 0) {
            // handled automatically via routing
            return;
        }
        else if (length > 0) {
            id = null;
        }
        else {
            if(UIHelper.evaluateMediaQuery(UIConstants.MEDIA_QUERY_MAX_WIDTH,UIConstants.MOBILE_TAB_SWITCH_WIDTH)) {
                this.showSelectRoot = true;
            }
            return;
        }

        this.openDirectory(id);
    }
    private refresh(refreshPath = true,nodes: Node[] = null) {
        // only refresh properties in this case
        if(nodes && nodes.length){
            this.updateNodes(nodes);
            return;
        }
        const search = this.searchQuery;
        const folder = this.currentFolder;
        this.currentFolder = null;
        this.searchQuery = null;
        this.selection = [];
        const path = this.path;
        if (refreshPath) {
            this.path = [];
        }
        setTimeout(() => {
            this.path = path;
            this.currentFolder = folder;
            this.searchQuery = search;
        });
    }

    private refreshRoute() {
        this.routeTo(
            this.root,
            !this.isRootFolder && this.currentFolder ? this.currentFolder.ref.id : null,
            this.searchQuery ? this.searchQuery.query : null
        );
    }
    private routeTo(root: string, node: string = null, search: string = null) {
        const params: any = { root, id: node, viewType: this.viewType, query: search, mainnav: this.mainnav };
        if (this.reurl) {
            params.reurl = this.reurl;
        }
        if (this.reurlDirectories) {
            params.applyDirectories = this.reurlDirectories;
        }
        this.router.navigate(['./'], { queryParams: params, relativeTo: this.route })
            .then((result: boolean) => {
                if (!result) {
                    this.refresh(false);
                }
            });
    }

    private showAlpha() {
        this.toast.showModalDialog('WORKSPACE.ALPHA_TITLE',
            'WORKSPACE.ALPHA_MESSAGE',
            DialogButton.getOk(() => this.hideDialog()),
            false
        );
    }

    private addToCollection(node: Node) {
        const nodes = this.getNodeList(node);
        this.addNodesToCollection = nodes;
    }
    private addToStream(node: Node) {
        const nodes = this.getNodeList(node);
        this.addNodesStream = nodes;
    }
    private createVariant(node: Node) {
        const nodes = this.getNodeList(node);
        this.variantNode = nodes[0];
    }

    private goToLogin() {
        RestHelper.goToLogin(this.router, this.config, this.isSafe ? RestConstants.SAFE_SCOPE : '');
    }

    private getRootFolderId() {
        if (this.root === 'MY_FILES') {
            return RestConstants.USERHOME;
        }
        if (this.root === 'SHARED_FILES') {
            return RestConstants.SHARED_FILES;
        }
        if (this.root === 'MY_SHARED_FILES') {
            return RestConstants.MY_SHARED_FILES;
        }
        if (this.root === 'TO_ME_SHARED_FILES') {
            return RestConstants.TO_ME_SHARED_FILES;
        }
        if (this.root === 'WORKFLOW_RECEIVE') {
            return RestConstants.WORKFLOW_RECEIVE;
        }
        return '';
    }

    private toggleView() {
        this.viewType = 1 - this.viewType;
        this.refreshRoute();
        if (this.viewType === 0) {
            this.viewToggle.icon = 'view_module';
        }
        else {
            this.viewToggle.icon = 'list';
        }

    }

    public listLTI() {
        this.showLtiTools = true;
    }

    private recoverScrollposition() {
        window.scrollTo(0, this.storage.get('workspace_scroll', 0));
    }

    private applyNode(node: Node, force = false) {
        /*if(node.isDirectory && !force){
            this.dialogTitle='WORKSPACE.APPLY_NODE.DIRECTORY_TITLE';
            this.dialogCancelable=true;
            this.dialogMessage='WORKSPACE.APPLY_NODE.DIRECTORY_MESSAGE';
            this.dialogMessageParameters={name:node.name};
            this.dialogButtons=DialogButton.getYesNo(()=>{
                this.dialogTitle=null;
            },()=>{
                this.dialogTitle=null;
                this.applyNode(node,true);
            });
            return;
        }*/
        NodeHelper.addNodeToLms(this.router, this.storage, node, this.reurl);
    }

    private updateNodeByParams(params: any, node: Node | any) {
        GlobalContainerComponent.finishPreloading();
        if (params.query) {
            this.doSearchFromRoute(params, node);
        }
        else {
            this.searchQuery = null;
            this.currentFolder = node;
            this.event.broadcastEvent(FrameEventsService.EVENT_NODE_FOLDER_OPENED, this.currentFolder);
        }
    }

    private canPasteInCurrentLocation() {
        const clip = (this.storage.get('workspace_clipboard') as ClipboardObject);
        return this.currentFolder
            && !this.searchQuery
            && clip
            && ((!clip.sourceNode || clip.sourceNode.ref.id !== this.currentFolder.ref.id) || clip.copy)
            && this.createAllowed;
    }

    private updateNodes(nodes: Node[]) {
        for(let node of this.currentNodes){
            const hit = nodes.filter((n) => n.ref.id === node.ref.id);
            if (hit && hit.length === 1) {
                Helper.copyObjectProperties(node, hit[0]);
            }
        }
    }
}
