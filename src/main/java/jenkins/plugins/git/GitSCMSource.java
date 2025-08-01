/*
 * The MIT License
 *
 * Copyright (c) 2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.git;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.RestrictedSince;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.scm.RepositoryBrowser;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.plugins.git.traits.GitBrowserSCMSourceTrait;
import jenkins.plugins.git.traits.GitSCMExtensionTrait;
import jenkins.plugins.git.traits.GitSCMExtensionTraitDescriptor;
import jenkins.plugins.git.traits.GitToolSCMSourceTrait;
import jenkins.plugins.git.traits.IgnoreOnPushNotificationTrait;
import jenkins.plugins.git.traits.RefSpecsSCMSourceTrait;
import jenkins.plugins.git.traits.RemoteNameSCMSourceTrait;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.SCMSourceOwners;
import jenkins.scm.api.trait.SCMHeadPrefilter;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.api.trait.SCMTrait;
import jenkins.scm.impl.TagSCMHeadCategory;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import jenkins.scm.impl.form.NamedArrayList;
import jenkins.scm.impl.trait.Discovery;
import jenkins.scm.impl.trait.Selection;
import jenkins.scm.impl.trait.WildcardSCMHeadFilterTrait;
import jenkins.security.FIPS140;
import jenkins.util.SystemProperties;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * A {@link SCMSource} that discovers branches in a git repository.
 */
public class GitSCMSource extends AbstractGitSCMSource {
    private static final String DEFAULT_INCLUDES = "*";

    private static final String DEFAULT_EXCLUDES = "";

    public static final Logger LOGGER = Logger.getLogger(GitSCMSource.class.getName());

    private final String remote;

    @CheckForNull
    private String credentialsId;

    static final String IGNORE_TAG_DISCOVERY_TRAIT_PROPERTY = GitSCMSource.class.getName() + ".IGNORE_TAG_DISCOVERY_TRAIT";

    /**
     * Ignore the tag discovery trait when fetching multibranch Pipelines.
     *
     * Git plugin versions 5.7.0 and earlier will always fetch tags
     * when scanning a multibranch Pipeline, whether or not the tag
     * discovery trait had been added. Releases after git plugin 5.7.0
     * honor the tag discovery trait when scanning a multibranch
     * Pipeline. If the tag discovery trait has been added, then tags
     * are fetched. If the tag discovery trait has not been added,
     * then tags are not fetched.
     *
     * If honoring the tag discovery trait causes problems for a user,
     * a Java property can be set during Jenkins startup to restore
     * the previous (buggy) behavior.
     *
     * Refer to the plugin documentation for more details.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL")
    public static /* not final */ boolean IGNORE_TAG_DISCOVERY_TRAIT =
            SystemProperties.getBoolean(IGNORE_TAG_DISCOVERY_TRAIT_PROPERTY);

    @Deprecated
    private transient String remoteName;

    @Deprecated
    private transient String rawRefSpecs;

    @Deprecated
    private transient String includes;

    @Deprecated
    private transient String excludes;

    @Deprecated
    private transient boolean ignoreOnPushNotifications;

    @Deprecated
    private transient GitRepositoryBrowser browser;

    @Deprecated
    private transient String gitTool;

    @Deprecated
    private transient List<GitSCMExtension> extensions;

    /**
     * Holds all the behavioural traits of this source.
     *
     * @since 3.4.0
     */
    private List<SCMSourceTrait> traits = new ArrayList<>();

    @DataBoundConstructor
    public GitSCMSource(String remote) {
       this.remote = remote;
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String credentialsId) {
        if (!isFIPSCompliantTLS(credentialsId, this.remote)) {
            LOGGER.log(Level.SEVERE, Messages.git_fips_url_notsecured());
            throw new IllegalArgumentException(Messages.git_fips_url_notsecured());
        }
        this.credentialsId = credentialsId;
    }

    @DataBoundSetter
    public void setTraits(List<SCMSourceTrait> traits) {
        this.traits = SCMTrait.asSetList(traits);
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("3.4.0")
    public GitSCMSource(String id, String remote, String credentialsId, String remoteName, String rawRefSpecs, String includes, String excludes, boolean ignoreOnPushNotifications) {
        super(id);
        this.remote = remote;
        this.credentialsId = credentialsId;
        List<SCMSourceTrait> traits = new ArrayList<>();
        traits.add(new BranchDiscoveryTrait());
        if (!DEFAULT_INCLUDES.equals(includes) || !DEFAULT_EXCLUDES.equals(excludes)) {
            traits.add(new WildcardSCMHeadFilterTrait(includes, excludes));
        }
        if (remoteName != null && !remoteName.isBlank() && !DEFAULT_REMOTE_NAME.equals(remoteName)) {
            traits.add(new RemoteNameSCMSourceTrait(remoteName));
        }
        if (ignoreOnPushNotifications) {
            traits.add(new IgnoreOnPushNotificationTrait());
        }
        RefSpecsSCMSourceTrait trait = asRefSpecsSCMSourceTrait(rawRefSpecs, remoteName);
        if (trait != null) {
            traits.add(trait);
        }
        setTraits(traits);
    }

    @Deprecated
    @Restricted(NoExternalUse.class)
    @RestrictedSince("3.4.0")
    public GitSCMSource(String id, String remote, String credentialsId, String includes, String excludes, boolean ignoreOnPushNotifications) {
        this(id, remote, credentialsId, null, null, includes, excludes, ignoreOnPushNotifications);
    }

    protected Object readResolve() throws ObjectStreamException {
        if (traits == null) {
            List<SCMSourceTrait> traits = new ArrayList<>();
            traits.add(new BranchDiscoveryTrait());
            if ((includes != null && !DEFAULT_INCLUDES.equals(includes))
                    || (excludes != null && !DEFAULT_EXCLUDES.equals(excludes))) {
                traits.add(new WildcardSCMHeadFilterTrait(includes, excludes));
            }
            if (extensions != null) {
                EXTENSIONS:
                for (GitSCMExtension extension : extensions) {
                    for (SCMSourceTraitDescriptor d : SCMSourceTrait.all()) {
                        if (d instanceof GitSCMExtensionTraitDescriptor descriptor) {
                            if (descriptor.getExtensionClass().isInstance(extension)) {
                                try {
                                    SCMSourceTrait trait = descriptor.convertToTrait(extension);
                                    if (trait != null) {
                                        traits.add(trait);
                                        continue EXTENSIONS;
                                    }
                                } catch (UnsupportedOperationException e) {
                                    LOGGER.log(Level.WARNING,
                                            "Could not convert " + extension.getClass().getName() + " to a trait", e);
                                }
                            }
                        }
                        LOGGER.log(Level.FINE, "Could not convert {0} to a trait (likely because this option does not "
                                + "make sense for a GitSCMSource)", getClass().getName());
                    }
                }
            }
            if (remoteName != null && !remoteName.isBlank() && !DEFAULT_REMOTE_NAME.equals(remoteName)) {
                traits.add(new RemoteNameSCMSourceTrait(remoteName));
            }
            if (gitTool != null && !gitTool.isBlank()) {
                traits.add(new GitToolSCMSourceTrait(gitTool));
            }
            if (browser != null) {
                traits.add(new GitBrowserSCMSourceTrait(browser));
            }
            if (ignoreOnPushNotifications) {
                traits.add(new IgnoreOnPushNotificationTrait());
            }
            RefSpecsSCMSourceTrait trait = asRefSpecsSCMSourceTrait(rawRefSpecs, remoteName);
            if (trait != null) {
                traits.add(trait);
            }
            setTraits(traits);
        }
        return this;
    }

    private RefSpecsSCMSourceTrait asRefSpecsSCMSourceTrait(String rawRefSpecs, String remoteName) {
        if (rawRefSpecs != null) {
            Set<String> defaults = new HashSet<>();
            defaults.add("+refs/heads/*:refs/remotes/origin/*");
            if (remoteName != null) {
                defaults.add("+refs/heads/*:refs/remotes/"+remoteName+"/*");
            }
            if (!defaults.contains(rawRefSpecs.trim())) {
                List<String> templates = new ArrayList<>();
                for (String rawRefSpec : rawRefSpecs.split(" ")) {
                    if (rawRefSpec == null || rawRefSpec.isBlank()) {
                        continue;
                    }
                    if (defaults.contains(rawRefSpec)) {
                        templates.add(AbstractGitSCMSource.REF_SPEC_DEFAULT);
                    } else {
                        templates.add(rawRefSpec);
                    }
                }
                if (!templates.isEmpty()) {
                    return new RefSpecsSCMSourceTrait(templates.toArray(new String[0]));
                }
            }
        }
        return null;
    }

    @Deprecated
    @Restricted(DoNotUse.class)
    @RestrictedSince("3.4.0")
    public boolean isIgnoreOnPushNotifications() {
        return SCMTrait.find(traits, IgnoreOnPushNotificationTrait.class) != null;
    }

    // For Stapler only
    @Restricted(DoNotUse.class)
    @DataBoundSetter
    public void setBrowser(GitRepositoryBrowser browser) {
        List<SCMSourceTrait> traits = new ArrayList<>(this.traits);
        traits.removeIf(scmSourceTrait -> scmSourceTrait instanceof GitBrowserSCMSourceTrait);
        if (browser != null) {
            traits.add(new GitBrowserSCMSourceTrait(browser));
        }
        setTraits(traits);
    }

    // For Stapler only
    @Restricted(DoNotUse.class)
    @DataBoundSetter
    public void setGitTool(String gitTool) {
        List<SCMSourceTrait> traits = new ArrayList<>(this.traits);
        gitTool = Util.fixEmptyAndTrim(gitTool);
        traits.removeIf(scmSourceTrait -> scmSourceTrait instanceof GitToolSCMSourceTrait);
        if (gitTool != null) {
            traits.add(new GitToolSCMSourceTrait(gitTool));
        }
        setTraits(traits);
    }

    // For Stapler only
    @Restricted(DoNotUse.class)
    @DataBoundSetter
    @Deprecated
    public void setExtensions(@CheckForNull List<GitSCMExtension> extensions) {
        List<SCMSourceTrait> traits = new ArrayList<>(this.traits);
        traits.removeIf(scmSourceTrait -> scmSourceTrait instanceof GitSCMExtensionTrait);
        EXTENSIONS:
        for (GitSCMExtension extension : Util.fixNull(extensions)) {
            for (SCMSourceTraitDescriptor d : SCMSourceTrait.all()) {
                if (d instanceof GitSCMExtensionTraitDescriptor descriptor) {
                    if (descriptor.getExtensionClass().isInstance(extension)) {
                        try {
                            SCMSourceTrait trait = descriptor.convertToTrait(extension);
                            if (trait != null) {
                                traits.add(trait);
                                continue EXTENSIONS;
                            }
                        } catch (UnsupportedOperationException e) {
                            LOGGER.log(Level.WARNING,
                                    "Could not convert " + extension.getClass().getName() + " to a trait", e);
                        }
                    }
                }
                LOGGER.log(Level.FINE, "Could not convert {0} to a trait (likely because this option does not "
                        + "make sense for a GitSCMSource)", extension.getClass().getName());
            }
        }
        setTraits(traits);
    }

    @Override
    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRemote() {
        return remote;
    }

    @Deprecated
    @Restricted(DoNotUse.class)
    @RestrictedSince("3.4.0")
    public String getRawRefSpecs() {
        String remoteName = null;
        RefSpecsSCMSourceTrait refSpecs = null;
        for (SCMSourceTrait trait : traits) {
            if (trait instanceof RemoteNameSCMSourceTrait sourceTrait) {
                remoteName = sourceTrait.getRemoteName();
                if (refSpecs != null) break;
            }
            if (trait instanceof RefSpecsSCMSourceTrait sourceTrait) {
                refSpecs = sourceTrait;
                if (remoteName != null) break;
            }
        }
        if (remoteName == null) {
            remoteName = AbstractGitSCMSource.DEFAULT_REMOTE_NAME;
        }
        if (refSpecs == null) {
            return AbstractGitSCMSource.REF_SPEC_DEFAULT
                    .replaceAll(AbstractGitSCMSource.REF_SPEC_REMOTE_NAME_PLACEHOLDER, remoteName);
        }
        StringBuilder result = new StringBuilder();
        boolean first = true;
        Pattern placeholder = Pattern.compile(AbstractGitSCMSource.REF_SPEC_REMOTE_NAME_PLACEHOLDER);
        for (String template : refSpecs.asStrings()) {
            if (first) {
                first = false;
            } else {
                result.append(' ');
            }
            result.append(placeholder.matcher(template).replaceAll(remoteName));
        }
        return result.toString();
    }

    @Deprecated
    @Override
    @Restricted(DoNotUse.class)
    @RestrictedSince("3.4.0")
    protected List<RefSpec> getRefSpecs() {
        return new GitSCMSourceContext<>(null, SCMHeadObserver.none()).withTraits(traits).asRefSpecs();
    }

    @NonNull
    @Override
    public List<SCMSourceTrait> getTraits() {
        return traits;
    }

    @Symbol("git")
    @Extension
    public static class DescriptorImpl extends SCMSourceDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.GitSCMSource_DisplayName();
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context,
                                                     @QueryParameter String remote,
                                                     @QueryParameter String credentialsId) {
            if (context == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER) ||
                context != null && !context.hasPermission(Item.EXTENDED_READ)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            context instanceof Queue.Task t ? Tasks.getAuthenticationOf(t) : ACL.SYSTEM,
                            context,
                            StandardUsernameCredentials.class,
                            URIRequirementBuilder.fromUri(remote).build(),
                            GitClient.CREDENTIALS_MATCHER)
                    .includeCurrentValue(credentialsId);
        }

        @RequirePOST
        public FormValidation doCheckRemote(@AncestorInPath Item item,
                                         @QueryParameter String credentialsId,
                                         @QueryParameter String remote) throws IOException, InterruptedException {
            if (item == null && !Jenkins.get().hasPermission(Jenkins.MANAGE) ||
                item != null && !item.hasPermission(Item.CONFIGURE)) {
                return FormValidation.warning("Not allowed to modify remote");
            }
            return isFIPSCompliantTLS(credentialsId, remote) ? FormValidation.ok() : FormValidation.error(hudson.plugins.git.Messages.git_fips_url_notsecured());
        }

        @RequirePOST
        public FormValidation doCheckCredentialsId(@AncestorInPath Item context,
                                                   @QueryParameter String remote,
                                                   @QueryParameter String value) {
            if (context == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER) ||
                context != null && !context.hasPermission(Item.EXTENDED_READ)) {
                return FormValidation.ok();
            }

            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                return FormValidation.ok();
            }

            remote = Util.fixEmptyAndTrim(remote);
            if (remote == null)
            // not set, can't check
            {
                return FormValidation.ok();
            }

            for (ListBoxModel.Option o : CredentialsProvider.listCredentialsInItem(
                    StandardUsernameCredentials.class,
                    context,
                    context instanceof Queue.Task t
                            ? Tasks.getAuthenticationOf2(t)
                            : ACL.SYSTEM2,
                    URIRequirementBuilder.fromUri(remote).build(),
                    GitClient.CREDENTIALS_MATCHER)) {
                if (Objects.equals(value, o.value)) {
                    // TODO check if this type of credential is acceptable to the Git client or does it merit warning
                    // NOTE: we would need to actually lookup the credential to do the check, which may require
                    // fetching the actual credential instance from a remote credentials store. Perhaps this is
                    // not required
                    return FormValidation.ok();
                }
            }
            // no credentials available, can't check
            return FormValidation.warning("Cannot find any credentials with id " + value);
        }

        @Deprecated
        @Restricted(NoExternalUse.class)
        @RestrictedSince("3.4.0")
        public GitSCM.DescriptorImpl getSCMDescriptor() {
            return (GitSCM.DescriptorImpl)Jenkins.getActiveInstance().getDescriptor(GitSCM.class);
        }

        @Deprecated
        @Restricted(DoNotUse.class)
        @RestrictedSince("3.4.0")
        public List<GitSCMExtensionDescriptor> getExtensionDescriptors() {
            return getSCMDescriptor().getExtensionDescriptors();
        }

        @Deprecated
        @Restricted(DoNotUse.class)
        @RestrictedSince("3.4.0")
        public List<Descriptor<RepositoryBrowser<?>>> getBrowserDescriptors() {
            return getSCMDescriptor().getBrowserDescriptors();
        }

        @Deprecated
        @Restricted(DoNotUse.class)
        @RestrictedSince("3.4.0")
        public boolean showGitToolOptions() {
            return getSCMDescriptor().showGitToolOptions();
        }

        @Deprecated
        @Restricted(DoNotUse.class)
        @RestrictedSince("3.4.0")
        public ListBoxModel doFillGitToolItems() {
            return getSCMDescriptor().doFillGitToolItems();
        }

        public List<NamedArrayList<? extends SCMSourceTraitDescriptor>> getTraitsDescriptorLists() {
            List<NamedArrayList<? extends SCMSourceTraitDescriptor>> result = new ArrayList<>();
            List<SCMSourceTraitDescriptor> descriptors =
                    SCMSourceTrait._for(this, GitSCMSourceContext.class, GitSCMBuilder.class);
            NamedArrayList.select(descriptors, Messages.within_Repository(),
                    NamedArrayList.anyOf(
                            NamedArrayList.withAnnotation(Selection.class),
                            NamedArrayList.withAnnotation(Discovery.class)
                    ),
                    true, result);
            NamedArrayList.select(descriptors, Messages.additional(), null, true, result);
            return result;
        }

        public List<SCMSourceTrait> getTraitsDefaults() {
            return Collections.singletonList(new BranchDiscoveryTrait());
        }

        @NonNull
        @Override
        protected SCMHeadCategory[] createCategories() {
            return new SCMHeadCategory[]{UncategorizedSCMHeadCategory.DEFAULT, TagSCMHeadCategory.DEFAULT};
        }
    }

    @Extension
    public static class ListenerImpl extends GitStatus.Listener {
        @Override
        public List<GitStatus.ResponseContributor> onNotifyCommit(String origin,
                                                                  URIish uri,
                                                                  @Nullable final String sha1,
                                                                  List<ParameterValue> buildParameters,
                                                                  String... branches) {
            List<GitStatus.ResponseContributor> result = new ArrayList<>();
            final boolean notified[] = {false};
            // run in high privilege to see all the projects anonymous users don't see.
            // this is safe because when we actually schedule a build, it's a build that can
            // happen at some random time anyway.
            try (ACLContext context = ACL.as(ACL.SYSTEM)) {
                if (branches.length > 0) {
                    final URIish u = uri;
                    for (final String branch: branches) {
                        SCMHeadEvent.fireNow(new SCMHeadEvent<String>(SCMEvent.Type.UPDATED, branch, origin){
                            @Override
                            public boolean isMatch(@NonNull SCMNavigator navigator) {
                                return false;
                            }

                            @NonNull
                            @Override
                            public String getSourceName() {
                                // we will never be called here as do not match any navigator
                                return u.getHumanishName();
                            }

                            @Override
                            public boolean isMatch(SCMSource source) {
                                if (source instanceof GitSCMSource git) {
                                    GitSCMSourceContext ctx =
                                            new GitSCMSourceContext<>(null, SCMHeadObserver.none())
                                                    .withTraits(git.getTraits());
                                    if (ctx.ignoreOnPushNotifications()) {
                                        return false;
                                    }
                                    URIish remote;
                                    try {
                                        remote = new URIish(git.getRemote());
                                    } catch (URISyntaxException e) {
                                        // ignore
                                        return false;
                                    }
                                    if (GitStatus.looselyMatches(u, remote)) {
                                        notified[0] = true;
                                        return true;
                                    }
                                    return false;
                                }
                                return false;
                            }

                            @NonNull
                            @Override
                            public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
                                if (source instanceof GitSCMSource git) {
                                    GitSCMSourceContext<?,?> ctx =
                                            new GitSCMSourceContext<>(null, SCMHeadObserver.none())
                                                    .withTraits(git.getTraits());
                                    if (ctx.ignoreOnPushNotifications()) {
                                        return Collections.emptyMap();
                                    }
                                    URIish remote;
                                    try {
                                        remote = new URIish(git.getRemote());
                                    } catch (URISyntaxException e) {
                                        // ignore
                                        return Collections.emptyMap();
                                    }
                                    if (GitStatus.looselyMatches(u, remote)) {
                                        GitBranchSCMHead head = new GitBranchSCMHead(branch);
                                        for (SCMHeadPrefilter filter: ctx.prefilters()) {
                                            if (filter.isExcluded(git, head)) {
                                                return Collections.emptyMap();
                                            }
                                        }
                                        return Collections.singletonMap(head,
                                                sha1 != null ? new GitBranchSCMRevision(head, sha1) : null);
                                    }
                                }
                                return Collections.emptyMap();
                            }

                            @Override
                            public boolean isMatch(@NonNull SCM scm) {
                                return false; // TODO rewrite the legacy event system to fire through SCM API
                            }
                        });
                    }
                } else {
                    for (final SCMSourceOwner owner : SCMSourceOwners.all()) {
                        for (SCMSource source : owner.getSCMSources()) {
                            if (source instanceof GitSCMSource git) {
                                GitSCMSourceContext<?, ?> ctx =
                                        new GitSCMSourceContext<>(null, SCMHeadObserver.none())
                                                .withTraits(git.getTraits());
                                if (ctx.ignoreOnPushNotifications()) {
                                    continue;
                                }
                                URIish remote;
                                try {
                                    remote = new URIish(git.getRemote());
                                } catch (URISyntaxException e) {
                                    // ignore
                                    continue;
                                }
                                if (GitStatus.looselyMatches(uri, remote)) {
                                    LOGGER.fine("Triggering the indexing of " + owner.getFullDisplayName()
                                            + " as a result of event from " + origin);
                                    triggerIndexing(owner, source);
                                    result.add(new GitStatus.ResponseContributor() {
                                        @Override
                                        @SuppressWarnings("deprecation")
                                        public void addHeaders(StaplerRequest2 req, StaplerResponse2 rsp) {
                                            // Calls a deprecated getAbsoluteUrl() method because this is a remote API case
                                            // as described in the Javadoc of the deprecated getAbsoluteUrl() method.
                                            rsp.addHeader("Triggered", owner.getAbsoluteUrl());
                                        }

                                        @Override
                                        public void writeBody(PrintWriter w) {
                                            w.println("Scheduled indexing of " + owner.getFullDisplayName());
                                        }
                                    });
                                    notified[0] = true;
                                }
                            }
                        }
                    }
                }
            }
            if (!notified[0]) {
                result.add(new GitStatus.MessageResponseContributor("No Git consumers using SCM API plugin for: " + uri.toString()));
            }
            return result;
        }

        @SuppressWarnings("deprecation")
        private void triggerIndexing(SCMSourceOwner owner, SCMSource source) {
            owner.onSCMSourceUpdated(source);
        }
    }
}
