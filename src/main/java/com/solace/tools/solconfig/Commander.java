package com.solace.tools.solconfig;

import com.solace.tools.solconfig.model.*;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Commander {
    final Logger logger = LoggerFactory.getLogger(Commander.class);
    @Getter private SempClient sempClient;
    @Setter private boolean curlOnly;
    @Setter private boolean useTemplate;

    public static Commander ofSempClient(SempClient sempClient){
        Commander commander = new Commander();
        commander.sempClient = sempClient;
        commander.setupSempSpec();
        return commander;
    }

    private void setupSempSpec() {
        SempSpec.setupByString(sempClient.getBrokerSpec());
    }

    public void backup(String resourceType, String[] objectNames, boolean isKeepDefault){
        exitOnObjectsNotExist(resourceType, objectNames);
        ConfigBroker configBroker = generateConfigFromBroker(resourceType, objectNames);

        configBroker.removeChildrenObjects(ConfigObject::isReservedObject, ConfigObject::isDeprecatedObject);
        configBroker.removeAttributes(AttributeType.PARENT_IDENTIFIERS, AttributeType.DEPRECATED);
        if (! isKeepDefault) {
            configBroker.removeAttributesWithDefaultValue();
        }
        configBroker.checkAttributeCombinations(); // keep requires attribute for backup
        System.out.println(configBroker);
    }

    public void delete(String resourceType, String[] objectNames) {
        exitOnObjectsNotExist(resourceType, objectNames);
        ConfigBroker configBroker = generateConfigFromBroker(resourceType, objectNames);
        configBroker.removeChildrenObjects(ConfigObject::isReservedObject, ConfigObject::isDeprecatedObject);

        var commandList = new RestCommandList();
        configBroker.forEachChild(configObject -> configObject.generateDeleteCommands(commandList));
        commandList.execute(sempClient, curlOnly);
    }

    /**
     * Generate a ConfigBroker object from the PS+ broker
     * @param resourceType of one resource type
     * @param objectNames of multiple objects
     * @return a new ConfigBroker object
     */
    private ConfigBroker generateConfigFromBroker(String resourceType, String[] objectNames) {
        ConfigBroker configBroker = new ConfigBroker();
        configBroker.setSempVersion(SempSpec.getSempVersion());
        configBroker.setOpaquePassword(sempClient.getOpaquePassword());

        var nameList = Arrays.asList(objectNames);
        if (nameList.contains("*")){
            nameList = List.of("*");
        }
        Map<String, String> childrenLinks = nameList.stream()
                .map(objectName -> Map.entry(objectName,
                        sempClient.buildAbsoluteUri(
                                String.format("/%s?where=%s==%s",
                                        resourceType, SempSpec.getTopResourceIdentifierKey(resourceType), objectName))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        getChildrenRecursively(configBroker, childrenLinks);
        return configBroker;
    }

    private void getChildrenRecursively(ConfigObject configObject, Map<String, String> childrenLinks){
        childrenLinks.entrySet().stream()
            .filter(e -> !e.getKey().equals("uri"))
            .forEach(e -> {
                String collectionName = Utils.getCollectionNameFromUri(e.getValue());
                var sempResponse = sempClient.getCollectionWithAbsoluteUri(e.getValue());
                if (sempResponse.isEmpty()){
                    return;
                }
                List<Map<String, Object>> data = sempResponse.getData();
                List<Map<String, String>> links = sempResponse.getLinks();
                for (int i = 0; i < data.size(); i++) {
                    var child = configObject.addChild(collectionName, data.get(i));
                    getChildrenRecursively(child, links.get(i));
                }
            });
    }

    public void create(Path confPath) {
        ConfigBroker configBroker = getConfigBrokerFromFile(confPath);
        configBroker.checkAttributeCombinations();
        sempClient.setOpaquePassword(configBroker.getOpaquePassword());
        if (!curlOnly){
            exitOnObjectsAlreadyExist(configBroker);
        }

        var commandList = new RestCommandList();
        configBroker.forEachChild(configObject -> configObject.generateCreateCommands(commandList));
        commandList.execute(sempClient, curlOnly);
    }

    private ConfigBroker getConfigBrokerFromFile(Path confPath) {
        ConfigBroker configFromFile = new ConfigBroker();
        Map<String, Object> map = SempClient.readMapFromJsonFile(confPath, this.useTemplate);
        configFromFile.addChildrenFromMap(map);
        configFromFile.sortChildren();
        configFromFile.setSempVersion(new SempVersion((String) map.get(SempSpec.SEMP_VERSION)));
        configFromFile.setOpaquePassword((String) map.get((SempSpec.OPAQUE_PASSWORD)));
        compareSempVersion(configFromFile);
        return configFromFile;
    }

    private void compareSempVersion(ConfigBroker configFromFile){
        if (configFromFile.getSempVersion().compareTo(SempSpec.getSempVersion()) == 0)
            return;
        if(Objects.nonNull(configFromFile.getOpaquePassword())){
            Utils.errPrintlnAndExit("OpaquePassword is only capable when the sempVersion [%s] of the config file is same as the broker's [%s].",
                    configFromFile.getSempVersion().getText(), SempSpec.getSempVersion().getText());
        }
        if (configFromFile.getSempVersion().compareTo(SempSpec.getSempVersion()) > 0) {
            System.out.printf("The sempVersion [%s] of the config file is newer than the broker's [%s], some objects/attributes may be not AVAILABLE!%n",
                    configFromFile.getSempVersion().getText(), SempSpec.getSempVersion().getText());
        } else if(configFromFile.getSempVersion().compareTo(SempSpec.getSempVersion()) < 0){
            System.out.printf("The sempVersion [%s] of the config file is older than the broker's [%s], some objects/attributes may be DEPRECATED!%n",
                    configFromFile.getSempVersion().getText(), SempSpec.getSempVersion().getText());
        }
    }



    private void exitOnObjectsNotExist(String resourceType, String[] objectNames) {
        checkObjectsExistence(resourceType, Arrays.asList(objectNames), false);
    }

    private void exitOnObjectsAlreadyExist(ConfigBroker configBroker) {
        checkConfigExistence(configBroker, true);
    }

    private void checkConfigExistence(ConfigBroker configBroker, boolean existOn) {
        var children= configBroker.getChildren().entrySet().stream().findFirst();
        if (children.isEmpty()) {
            Utils.errPrintlnAndExit("The configuration file is empty!");
        }else {
            var nameList = children.get().getValue().stream()
                    .map(ConfigObject::getObjectId)
                    .collect(Collectors.toList());
            checkObjectsExistence(children.get().getKey(), nameList, existOn);
        }
    }

    private void checkObjectsExistence(String resourceTypeFullName, List<String> objectNames, boolean existOn) {
        var objects = sempClient.checkIfObjectsExist(resourceTypeFullName, objectNames);
        var resultSet = objects.stream()
                .filter(e -> e.getValue() == existOn)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (resultSet.isEmpty()) {
            return;
        }
        var typeAbbr = SempSpec.RES_ABBR.ofFullName(resourceTypeFullName);
        Utils.errPrintlnAndExit("Resource %s[%s] %s exist!",
                typeAbbr,
                String.join(", ", resultSet),
                existOn ? "already" : "doesn't");
    }

    private void exitOnObjectsNotExist(ConfigBroker configFile) {
        configFile.forEachChild(obj ->
                checkObjectsExistence(obj.getCollectionName(), List.of(obj.getObjectId()), false));
    }

    /**
     * Generate a ConfigBroker object which has the same top objects as the input configFile
     */
    private ConfigBroker generateConfigFromBroker(ConfigBroker configFile) {
        ConfigBroker configBroker = new ConfigBroker();
        configBroker.setSempVersion(SempSpec.getSempVersion());

        Map<String, String> childrenLinks = new TreeMap<>();
        configFile.forEachChild(configObject -> {
            String resourceType = configObject.getCollectionName();
            String objectName = configObject.getObjectId();
            childrenLinks.put(objectName,
                    sempClient.buildAbsoluteUri(String.format("/%s?where=%s==%s",
                            resourceType, SempSpec.getTopResourceIdentifierKey(resourceType), objectName)));
        });

        getChildrenRecursively(configBroker, childrenLinks);
        return configBroker;
    }

    public void update(Path confPath, boolean isNoDelete, boolean isSkipVPN) {
        ConfigBroker configFile = getConfigBrokerFromFile(confPath);
        exitOnObjectsNotExist(configFile);

        sempClient.setOpaquePassword(configFile.getOpaquePassword());
        ConfigBroker configBroker = generateConfigFromBroker(configFile);
        List.of(configFile, configBroker).forEach(cb->{
            cb.removeChildrenObjects(ConfigObject::isReservedObject, ConfigObject::isDeprecatedObject);
            cb.removeAttributes(
                    AttributeType.PARENT_IDENTIFIERS,
                    AttributeType.DEPRECATED,
                    AttributeType.BROKER_SPECIFIC);
            cb.removeAttributesWithDefaultValue();
            cb.checkAttributeCombinations();
        });

        if (sempClient.isCloudInstance()) {
            // this is a cloud instance, so ignore the objects could not be updated by SEMPv2
            logger.warn("Targeted broker is on Solace Cloud, objects {} will be ignored",
                    SempSpec.SPEC_PATHS_OF_OBJECTS_OF_CLOUD_INSTANCE);
            configFile.ignoreObjectsForCloudInstance();
        }

        if (isSkipVPN){
            // ignore update of Message-VPN settings
            logger.warn("Message-VPN settings won't be updated");
            configFile.ignoreMsgVPNObject();
        }

        var deleteCommandList = new RestCommandList();
        var createCommandList = new RestCommandList();
        var updateCommandList = new RestCommandList();
        var enableCommandList = new RestCommandList();
        configBroker.generateUpdateCommands(configFile, deleteCommandList, updateCommandList, createCommandList, enableCommandList);

        var allCommands = createCommandList.addAll(updateCommandList);
        if (!isNoDelete){
            allCommands.addAll(deleteCommandList);
        }
        allCommands.addAll(enableCommandList);
        if (allCommands.sieze()>0){
            allCommands.execute(sempClient, curlOnly);
        }else {
            Utils.errPrintlnAndExit("Configuration file %s is identical to the existing objects.", confPath.toAbsolutePath());
        }
    }

    public void printSpec() {
        System.out.println(SempSpec.toPrettyString());
    }

    public void integrationTest() {
        System.out.println("## spec");
        printSpec();
        var path = "examples/template/demo_vpn.json";

        System.out.println("## create "+path);
        create(Path.of(path));
        var type = "msgVpns";
        var vpn = new String[] {"Demo"};

        System.out.printf("## backup %s %s%n", type, vpn[0]);
        backup(type, vpn, false);

        System.out.println("## update "+path);
        update(Path.of("examples/template/demo_vpn.json"), false, false);

        System.out.printf("## delete %s %s%n", type, vpn[0]);
        delete(type, vpn);
    }
}
