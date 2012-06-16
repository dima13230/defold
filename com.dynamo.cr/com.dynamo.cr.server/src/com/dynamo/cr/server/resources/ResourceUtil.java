package com.dynamo.cr.server.resources;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.util.StringUtils;

import com.dynamo.cr.proto.Config.Configuration;
import com.dynamo.cr.protocol.proto.Protocol.ProductInfo;
import com.dynamo.cr.protocol.proto.Protocol.ProjectInfo;
import com.dynamo.cr.protocol.proto.Protocol.UserInfo;
import com.dynamo.cr.server.Server;
import com.dynamo.cr.server.model.Product;
import com.dynamo.cr.server.model.Project;
import com.dynamo.cr.server.model.User;

public class ResourceUtil {
    public static UserInfo createUserInfo(User user) {
        UserInfo userInfo = UserInfo.newBuilder()
            .setId(user.getId())
            .setEmail(user.getEmail())
            .setFirstName(user.getFirstName())
            .setLastName(user.getLastName())
            .build();
        return userInfo;
    }

    /*
     * TODO: We should perhaps move all configuration that requires logic
     * to a setup-phase in Server? Same for getGitBaseUri below
     * Currently Configuration is a immutable configuration data-structure (protobuf)
     * We should perhaps derive settings to a new class with git-base-path etc
     */
    public static String getGitBasePath(Configuration configuration) {
        String repositoryRoot = FilenameUtils.normalize(configuration.getRepositoryRoot(), true);
        List<String> repositoryRootList = Arrays.asList(repositoryRoot.split("/"));
        if (repositoryRootList.size() < 1) {
            throw new RuntimeException("repositoryRoot path must contain at least 1 element");
        } else if (repositoryRootList.size() == 1) {
            repositoryRootList.add(0, ".");
        }

        String basePath = StringUtils.join(repositoryRootList.subList(0, repositoryRootList.size()-1), "/");
        basePath = new File(basePath).getAbsolutePath();
        return basePath;
    }

    public static String getGitBaseUri(Configuration configuration) {
        String repositoryRoot = FilenameUtils.normalize(configuration.getRepositoryRoot(), true);
        List<String> repositoryRootList = Arrays.asList(repositoryRoot.split("/"));
        if (repositoryRootList.size() < 1) {
            throw new RuntimeException("repositoryRoot path must contain at least 1 element");
        } else if (repositoryRootList.size() == 1) {
            repositoryRootList.add(0, ".");
        }

        String baseUri = "/" + repositoryRootList.get(repositoryRootList.size()-1);
        return baseUri;
    }

    public static ProjectInfo createProjectInfo(Configuration configuration, User user, Project project) {
        ProjectInfo.Builder b = ProjectInfo.newBuilder()
            .setId(project.getId())
            .setName(project.getName())
            .setDescription(project.getDescription())
            .setOwner(createUserInfo(project.getOwner()))
            .setCreated(project.getCreated().getTime())
            .setLastUpdated(project.getLastUpdated().getTime())
            .setRepositoryUrl(String.format("http://%s:%d%s/%d", configuration.getHostname(),
                    configuration.getServicePort(),
                    getGitBaseUri(configuration),
                    project.getId()));

            if (Server.getEngineFile(configuration, Long.toString(project.getId()), "ios").exists()) {
                String key = Server.getEngineDownloadKey(project);
                String url = String.format("http://%s:%d/projects/%d/%d/engine_manifest/ios/%s",
                                           configuration.getHostname(),
                                           configuration.getServicePort(),
                                           user.getId(),
                                           project.getId(),
                                           key);

                b.setIOSExecutableUrl(url);
            }

        for (User u : project.getMembers()) {
            b.addMembers(createUserInfo(u));
        }

        return b.build();
    }

    public static ProductInfo createProductInfo(Product product) {
        ProductInfo.Builder b = ProductInfo.newBuilder().setId(product.getId()).setName(product.getName())
                .setMaxMemberCount(product.getMaxMemberCount());
        return b.build();
    }
}
