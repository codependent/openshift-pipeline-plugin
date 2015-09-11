package com.openshift.openshiftjenkinsbuildutils;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.internal.restclient.OpenShiftAPIVersion;
import com.openshift.internal.restclient.URLBuilder;
import com.openshift.internal.restclient.http.HttpClientException;
import com.openshift.restclient.ClientFactory;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ISSLCertificateCallback;
import com.openshift.restclient.OpenShiftException;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.authorization.TokenAuthorizationStrategy;
import com.openshift.restclient.capability.CapabilityVisitor;
import com.openshift.restclient.capability.ICapability;
import com.openshift.restclient.capability.resources.IBuildTriggerable;
import com.openshift.restclient.capability.resources.IPodLogRetrieval;
import com.openshift.restclient.http.IHttpClient;
import com.openshift.restclient.model.IBuild;
import com.openshift.restclient.model.IBuildConfig;
import com.openshift.restclient.model.IDeploymentConfig;
import com.openshift.restclient.model.IPod;
import com.openshift.restclient.model.IProject;
import com.openshift.restclient.model.IReplicationController;
import com.openshift.restclient.model.IResource;

import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenShift {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link OpenShiftBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked. 
 *
 * @author Gabe Montero
 */
public class OpenShiftBuilder extends Builder implements ISSLCertificateCallback {

    private String apiURL = "https://openshift.default.svc.cluster.local";
    private String bldCfg = "frontend";
    private String nameSpace = "test";
    private String authToken = "";
    
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftBuilder(String apiURL, String bldCfg, String nameSpace, String authToken) {
        this.apiURL = apiURL;
        this.bldCfg = bldCfg;
        this.nameSpace = nameSpace;
        this.authToken = authToken;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getApiURL() {
		return apiURL;
	}

	public String getBldCfg() {
		return bldCfg;
	}

	public String getNameSpace() {
		return nameSpace;
	}
	
	public String getAuthToken() {
		return authToken;
	}

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
    	System.setProperty(ICapability.OPENSHIFT_BINARY_LOCATION, Constants.OC_LOCATION);
    	listener.getLogger().println("OpenShiftBuilder in perform");
    	
    	// obtain auth token from defined spot in OpenShift Jenkins image
    	authToken = Auth.deriveAuth(authToken, listener);
    	
    	String bldId = null;
    	
    	// get oc client (sometime REST, sometimes Exec of oc command
    	IClient client = new ClientFactory().create(apiURL, this);
    	
    	if (client != null) {
    		// seed the auth
        	client.setAuthorizationStrategy(new TokenAuthorizationStrategy(this.authToken));
        	
        	// get BuildConfig ref
        	IBuildConfig bc = client.get(ResourceKind.BUILD_CONFIG, bldCfg, nameSpace);

        	listener.getLogger().println("OpenShiftBuilder build config retrieved " + bc);
        	
        	if (bc != null) {
        		
        		// Trigger / start build
    			IBuild bld = bc.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {

    				public IBuild visit(IBuildTriggerable triggerable) {
    					return triggerable.trigger();
    				}
    			}, null);
    			
    			if(bld == null) {
    				listener.getLogger().println("OpenShiftBuilder triggered build is null");
    				return false;
    			} else {
    				bldId = bld.getName();
    				listener.getLogger().println("OpenShiftBuilder triggered build id is " + bldId);
    				
    				//TODO look at the two buildlogs.go files in origin
    				// and change to DefaultClient.java I made
    				//IResource bldlogs = ((com.openshift.internal.restclient.DefaultClient)client).get(ResourceKind.BUILD, bldId, nameSpace, "log");
    				
    				
    				boolean foundPod = false;
    				String bldState = null;
    				long startTime = System.currentTimeMillis();
    				
    				// Now find build Pod, attempt to dump the logs to the Jenkins console
    				while (!foundPod && startTime > (System.currentTimeMillis() - 60000)) {
    					
    					// fetch current list of pods ... this has proven to not be immediate in finding latest
    					// entries when compared with say running oc from the cmd line
        				List<IPod> pods = client.list(ResourceKind.POD, nameSpace);
        				for (IPod pod : pods) {
        					listener.getLogger().println("OpenShiftBuilder found pod " + pod.getName());
     
        					// build pod starts with build id
        					if(pod.getName().startsWith(bldId)) {
        						foundPod = true;
        						listener.getLogger().println("OpenShiftBuilder found build pod " + pod);
        						
            					// get log "retrieve" and dump build logs
            					IPodLogRetrieval logger = pod.getCapability(IPodLogRetrieval.class);
            					listener.getLogger().println("OpenShiftBuilder obtained pod logger " + logger);
            					
            					if (logger != null) {
            						
            						// get internal OS Java REST Client error if access pod logs while bld is in Pending state
            						// instead of Running, Complete, or Failed
            						long currTime = System.currentTimeMillis();
            						while (System.currentTimeMillis() < (currTime + 60000)) {
            							bld = client.get(ResourceKind.BUILD, bldId, nameSpace);
            							bldState = bld.getStatus();
            							listener.getLogger().println("OpenShiftBuilder bld state:  " + bldState);
            							if ("Pending".equals(bldState)) {
            								try {
    											Thread.sleep(1000);
    										} catch (InterruptedException e) {
    										}
            							} else {
            								break;
            							}
            						}
            						
            						
            						// create stream and copy bytes
            						InputStream logs = new BufferedInputStream(logger.getLogs(true));
            						int b;
            						try {
        								while ((b = logs.read()) != -1) {
        									listener.getLogger().write(b);
        								}
        							} catch (IOException e) {
        								e.printStackTrace(listener.getLogger());
        							} finally {
        								try {
        									logs.close();
        								} catch (final IOException e) {
        									e.printStackTrace(listener.getLogger());
        								}
        							}
            						
            						// if the deployment config for this app specifies a desired replica count of 
            						// of greater than zero, let's also confirm the deployment occurs;
            						// first, get the deployment config
//            						Map<String,IDeploymentConfig> dcs = Deployment.getDeploymentConfigs(client, nameSpace, listener);
//            						boolean dcWithReplicas = false;
//    								boolean haveDep = false;
//            						for (String key : dcs.keySet()) {
//            							if (key.startsWith(bldCfg)) {
//            								IDeploymentConfig dc = dcs.get(key);
//            								if (dc.getReplicas() > 0) {
//            									dcWithReplicas = true;
//            									
//                        						listener.getLogger().println("OpenShiftBuilder checking if deployment out there");
//                        						
//                								// confirm the deployment has kicked in from completed build
//                								currTime = System.currentTimeMillis();
//                								while (System.currentTimeMillis() < (currTime + 60000)) {
//                						        	Map<String, IReplicationController> rcs = Deployment.getDeployments(client, nameSpace, listener);
//                						        	for (String rckey : rcs.keySet()) {
//                						        		if (rckey.startsWith(bldId)) {
//                						        			listener.getLogger().println("OpenShiftBuilder found dep " + key + ":  " + rcs.get(key));
//                						        			haveDep = true;
//                						        			break;
//                						        		}
//                						        	}
//                						        	
//                						        	if (haveDep)
//                						        		break;
//                								}
//            								}
//            							}
//            							
//            							if (haveDep)
//            								break;
//            						}
//
//    								
//    								if (dcWithReplicas && haveDep)
//    									return true;
//    								
//    								if (!dcWithReplicas)
//    									return true;
    								
            					} else {
            						listener.getLogger().println("OpenShiftBuilder logger for pod " + pod.getName() + " not available");
            						bldState = pod.getStatus();
            					}
        					}
        					
        					if (foundPod)
        						break;
        				}
        				
        				try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
						}
        				
    				}
    				
    				if (!foundPod) {
    					listener.getLogger().println("OpenShiftBuilder did not find build pod for " + bldId + " in time.  If possible interrogate the OpenShift server with the oc command and inspect the server logs.");
    					return false;
    				}
    				
					long currTime = System.currentTimeMillis();
					while (System.currentTimeMillis() < (currTime + 60000)) {
						bld = client.get(ResourceKind.BUILD, bldId, nameSpace);
						bldState = bld.getStatus();
						listener.getLogger().println("OpenShiftBuilder post bld launch bld state:  " + bldState);
						if (!bldState.equals("Complete")) {
							try {
								Thread.sleep(1000);
							} catch (InterruptedException e) {
							}
						} else {
							break;
						}
					}
    				if (bldState == null || !bldState.equals("Complete")) {
    					listener.getLogger().println("OpenShiftBuilder build state is " + bldState + ".  If possible interrogate the OpenShift server with the oc command and inspect the server logs");
    					return false;
    				} else 
    					return true;
    				
    				
    			}
        		
        		
        	} else {
        		listener.getLogger().println("OpenShiftBuilder could not get build config");
        		return false;
        	}
    	} else {
    		listener.getLogger().println("OpenShiftBuilder could not get oc client");
    		return false;
    	}

    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link OpenShiftBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the various fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApiURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set apiURL");
            return FormValidation.ok();
        }

        public FormValidation doCheckBldCfg(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set bldCfg");
            return FormValidation.ok();
        }

        public FormValidation doCheckNameSpace(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set nameSpace");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Perform builds in OpenShift";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
            save();
            return super.configure(req,formData);
        }

    }

	@Override
	public boolean allowCertificate(X509Certificate[] chain) {
		return true;
	}

	@Override
	public boolean allowHostname(String hostname, SSLSession session) {
		return true;
	}
}

