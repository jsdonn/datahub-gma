package com.linkedin.metadata.dao.utils;

import com.linkedin.common.urn.Urn;
import com.linkedin.data.DataMap;
import com.linkedin.data.schema.ArrayDataSchema;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.data.schema.RecordDataSchema;
import com.linkedin.data.schema.TyperefDataSchema;
import com.linkedin.data.schema.UnionDataSchema;
import com.linkedin.data.template.DataTemplate;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.template.UnionTemplate;
import com.linkedin.data.template.WrappingArrayTemplate;
import com.linkedin.metadata.aspect.AspectVersion;
import com.linkedin.metadata.dummy.DummySnapshot;
import com.linkedin.metadata.validator.AspectValidator;
import com.linkedin.metadata.validator.DeltaValidator;
import com.linkedin.metadata.validator.DocumentValidator;
import com.linkedin.metadata.validator.EntityValidator;
import com.linkedin.metadata.validator.InvalidSchemaException;
import com.linkedin.metadata.validator.RelationshipValidator;
import com.linkedin.metadata.validator.SnapshotValidator;
import com.linkedin.metadata.validator.ValidationUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.reflections.Reflections;


public class ModelUtils {

  private static final ClassLoader CLASS_LOADER = DummySnapshot.class.getClassLoader();
  private static final String METADATA_AUDIT_EVENT_PREFIX = "METADATA_AUDIT_EVENT";
  private static final String URN_FIELD = "urn";
  private static final String ASPECTS_FIELD = "aspects";

  private ModelUtils() {
    // Util class
  }

  /**
   * Gets the corresponding aspect name for a specific aspect type.
   *
   * @param aspectClass the aspect type
   * @param <T> must be a valid aspect type
   * @return the corresponding aspect name, which is actually the FQCN of type
   */
  public static <T extends DataTemplate> String getAspectName(@Nonnull Class<T> aspectClass) {
    return aspectClass.getCanonicalName();
  }

  /**
   * Gets the corresponding {@link Class} for a given aspect name.
   *
   * @param aspectName the name returned from {@link #getAspectName(Class)}
   * @return the corresponding {@link Class}
   */
  @Nonnull
  public static Class<? extends RecordTemplate> getAspectClass(@Nonnull String aspectName) {
    return getClassFromName(aspectName, RecordTemplate.class);
  }

  /**
   * Returns all supported aspects from an aspect union.
   *
   * @param aspectUnionClass the aspect union type to extract supported aspects from
   * @param <ASPECT_UNION> must be a valid aspect union defined in com.linkedin.metadata.aspect
   * @return a set of supported aspects
   */
  @Nonnull
  public static <ASPECT_UNION extends UnionTemplate> Set<Class<? extends RecordTemplate>> getValidAspectTypes(
      @Nonnull Class<ASPECT_UNION> aspectUnionClass) {

    AspectValidator.validateAspectUnionSchema(aspectUnionClass);

    Set<Class<? extends RecordTemplate>> validTypes = new HashSet<>();
    for (UnionDataSchema.Member member : ValidationUtils.getUnionSchema(aspectUnionClass).getMembers()) {
      String fqcn = null;
      if (member.getType().getType() == DataSchema.Type.RECORD) {
        fqcn = ((RecordDataSchema) member.getType()).getBindingName();
      } else if (member.getType().getType() == DataSchema.Type.TYPEREF
          && member.getType().getDereferencedType() == DataSchema.Type.RECORD) {
        fqcn = ((RecordDataSchema) member.getType().getDereferencedDataSchema()).getBindingName();
      }

      if (fqcn != null) {
        try {
          validTypes.add(CLASS_LOADER.loadClass(fqcn).asSubclass(RecordTemplate.class));
        } catch (ClassNotFoundException | ClassCastException e) {
          throw new RuntimeException(e);
        }
      }
    }

    return validTypes;
  }

  /**
   * Gets a {@link Class} from its FQCN.
   */
  @Nonnull
  public static <T> Class<? extends T> getClassFromName(@Nonnull String className, @Nonnull Class<T> parentClass) {
    try {
      return CLASS_LOADER.loadClass(className).asSubclass(parentClass);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(className + " cannot be found", e);
    }
  }

  /**
   * Gets a snapshot class given its FQCN.
   *
   * @param className FQCN of snapshot class
   * @return snapshot class that extends {@link RecordTemplate}, associated with className
   */
  @Nonnull
  public static Class<? extends RecordTemplate> getMetadataSnapshotClassFromName(@Nonnull String className) {
    Class<? extends RecordTemplate> snapshotClass = getClassFromName(className, RecordTemplate.class);
    SnapshotValidator.validateSnapshotSchema(snapshotClass);
    return snapshotClass;
  }

  /**
   * Extracts the "urn" field from a snapshot.
   *
   * @param snapshot the snapshot to extract urn from
   * @param <SNAPSHOT> must be a valid snapshot model defined in com.linkedin.metadata.snapshot
   * @return the extracted {@link Urn}
   */
  @Nonnull
  public static <SNAPSHOT extends RecordTemplate> Urn getUrnFromSnapshot(@Nonnull SNAPSHOT snapshot) {
    SnapshotValidator.validateSnapshotSchema(snapshot.getClass());
    final Urn urn = RecordUtils.getRecordTemplateField(snapshot, URN_FIELD, urnClassForSnapshot(snapshot.getClass()));
    if (urn == null) {
      ValidationUtils.throwNullFieldException(URN_FIELD);
    }
    return urn;
  }

  /**
   * Get Urn based on provided urn string and urn class.
   *
   * @param <URN> must be a valid URN type that extends {@link Urn}
   * @param urn urn string
   * @param urnClass urn class
   * @return converted urn
   */
  public static <URN extends Urn> URN getUrnFromString(@Nullable String urn, @Nonnull Class<URN> urnClass) {
    if (urn == null) {
      return null;
    }

    try {
      final Method getUrn = urnClass.getMethod("createFromString", String.class);
      return urnClass.cast(getUrn.invoke(null, urn));
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalArgumentException("URN conversion error for " + urn, e);
    }
  }

  /**
   * Get the entity type of urn inside a snapshot class.
   * @param snapshot a snapshot class
   * @return entity type of urn
   */
  @Nonnull
  public static <SNAPSHOT extends RecordTemplate> String getUrnTypeFromSnapshot(@Nonnull Class<SNAPSHOT> snapshot) {
    try {
      return (String) snapshot.getMethod("getUrn").getReturnType().getField("ENTITY_TYPE").get(null);
    } catch (Exception ignored) {
      throw new IllegalArgumentException(String.format("The snapshot class %s is not valid.", snapshot.getCanonicalName()));
    }
  }

  /**
   * Similar to {@link #getUrnFromSnapshot(RecordTemplate)} but extracts from a Snapshot union instead.
   */
  @Nonnull
  public static Urn getUrnFromSnapshotUnion(@Nonnull UnionTemplate snapshotUnion) {
    return getUrnFromSnapshot(RecordUtils.getSelectedRecordTemplateFromUnion(snapshotUnion));
  }

  /**
   * Extracts the "urn" field from a delta.
   *
   * @param delta the delta to extract urn from
   * @param <DELTA> must be a valid delta model defined in com.linkedin.metadata.delta
   * @return the extracted {@link Urn}
   */
  @Nonnull
  public static <DELTA extends RecordTemplate> Urn getUrnFromDelta(@Nonnull DELTA delta) {
    DeltaValidator.validateDeltaSchema(delta.getClass());
    return RecordUtils.getRecordTemplateField(delta, URN_FIELD, urnClassForDelta(delta.getClass()));
  }

  /**
   * Similar to {@link #getUrnFromDelta(RecordTemplate)} but extracts from a delta union instead.
   */
  @Nonnull
  public static Urn getUrnFromDeltaUnion(@Nonnull UnionTemplate deltaUnion) {
    return getUrnFromDelta(RecordUtils.getSelectedRecordTemplateFromUnion(deltaUnion));
  }

  /**
   * Extracts the "urn" field from a search document.
   *
   * @param document the document to extract urn from
   * @param <DOCUMENT> must be a valid document model defined in com.linkedin.metadata.search
   * @return the extracted {@link Urn}
   */
  @Nonnull
  public static <DOCUMENT extends RecordTemplate> Urn getUrnFromDocument(@Nonnull DOCUMENT document) {
    DocumentValidator.validateDocumentSchema(document.getClass());
    final Urn urn = RecordUtils.getRecordTemplateField(document, URN_FIELD, urnClassForDocument(document.getClass()));
    if (urn == null) {
      ValidationUtils.throwNullFieldException(URN_FIELD);
    }
    return urn;
  }

  /**
   * Extracts the "urn" field from an entity.
   *
   * @param entity the entity to extract urn from
   * @param <ENTITY> must be a valid entity model defined in com.linkedin.metadata.entity
   * @return the extracted {@link Urn}
   */
  @Nonnull
  public static <ENTITY extends RecordTemplate> Urn getUrnFromEntity(@Nonnull ENTITY entity) {
    EntityValidator.validateEntitySchema(entity.getClass());
    final Urn urn = RecordUtils.getRecordTemplateField(entity, URN_FIELD, urnClassForDocument(entity.getClass()));
    if (urn == null) {
      ValidationUtils.throwNullFieldException(URN_FIELD);
    }
    return urn;
  }

  /**
   * Extracts the fields with type urn from a relationship.
   *
   * @param relationship the relationship to extract urn from
   * @param <RELATIONSHIP> must be a valid relationship model defined in com.linkedin.metadata.relationship
   * @param fieldName name of the field with type urn
   * @return the extracted {@link Urn}
   */
  @Nonnull
  private static <RELATIONSHIP extends RecordTemplate> Urn getUrnFromRelationship(@Nonnull RELATIONSHIP relationship,
      @Nonnull String fieldName) {
    RelationshipValidator.validateRelationshipSchema(relationship.getClass());
    final Urn urn = RecordUtils.getRecordTemplateField(relationship, fieldName, urnClassForRelationship(relationship.getClass(), fieldName));
    if (urn == null) {
      ValidationUtils.throwNullFieldException(URN_FIELD);
    }
    return urn;
  }

  /**
   * Similar to {@link #getUrnFromRelationship} but extracts from a delta union instead.
   */
  @Nonnull
  public static <RELATIONSHIP extends RecordTemplate> Urn getSourceUrnFromRelationship(
      @Nonnull RELATIONSHIP relationship) {
    return getUrnFromRelationship(relationship, "source");
  }

  /**
   * Similar to {@link #getUrnFromRelationship} but extracts from a delta union instead.
   */
  @Nonnull
  public static <RELATIONSHIP extends RecordTemplate> Urn getDestinationUrnFromRelationship(
      @Nonnull RELATIONSHIP relationship) {
    return getUrnFromRelationship(relationship, "destination");
  }

  /**
   * Extracts the list of aspects in a snapshot.
   *
   * @param snapshot the snapshot to extract aspects from
   * @param <SNAPSHOT> must be a valid snapshot model defined in com.linkedin.metadata.snapshot
   * @return the extracted list of aspects
   */
  @Nonnull
  public static <SNAPSHOT extends RecordTemplate> List<RecordTemplate> getAspectsFromSnapshot(
      @Nonnull SNAPSHOT snapshot) {
    SnapshotValidator.validateSnapshotSchema(snapshot.getClass());
    return getAspects(snapshot);
  }

  /**
   * Extracts given aspect from a snapshot.
   *
   * @param snapshot the snapshot to extract the aspect from
   * @param <SNAPSHOT> must be a valid snapshot model defined in com.linkedin.metadata.snapshot
   * @param aspectClass the aspect class type to extract from snapshot
   * @return the extracted aspect
   */
  @Nonnull
  public static <SNAPSHOT extends RecordTemplate, ASPECT extends DataTemplate> Optional<ASPECT> getAspectFromSnapshot(
      @Nonnull SNAPSHOT snapshot, @Nonnull Class<ASPECT> aspectClass) {

    return getAspectsFromSnapshot(snapshot).stream()
        .filter(aspect -> aspect.getClass().equals(aspectClass))
        .findFirst()
        .map(aspectClass::cast);
  }

  /**
   * Similar to {@link #getAspectsFromSnapshot(RecordTemplate)} but extracts from a snapshot union instead.
   */
  @Nonnull
  public static List<RecordTemplate> getAspectsFromSnapshotUnion(@Nonnull UnionTemplate snapshotUnion) {
    return getAspects(RecordUtils.getSelectedRecordTemplateFromUnion(snapshotUnion));
  }

  @Nonnull
  private static List<RecordTemplate> getAspects(@Nonnull RecordTemplate snapshot) {
    final Class<? extends WrappingArrayTemplate> clazz = getAspectsArrayClass(snapshot.getClass());

    final WrappingArrayTemplate aspectArray = RecordUtils.getRecordTemplateWrappedField(snapshot, ASPECTS_FIELD, clazz);
    if (aspectArray == null) {
      ValidationUtils.throwNullFieldException(ASPECTS_FIELD);
    }

    final List<RecordTemplate> aspects = new ArrayList<>();
    aspectArray.forEach(item -> aspects.add(RecordUtils.getSelectedRecordTemplateFromUnion((UnionTemplate) item)));
    return aspects;
  }

  /**
   * Creates a snapshot with its urn field set.
   *
   * @param snapshotClass the type of snapshot to create
   * @param urn value for the urn field
   * @param aspects value for the aspects field
   * @param <SNAPSHOT> must be a valid snapshot model defined in com.linkedin.metadata.snapshot
   * @param <ASPECT_UNION> must be a valid aspect union defined in com.linkedin.metadata.aspect
   * @param <URN> must be a valid URN type
   * @return the created snapshot
   */
  @Nonnull
  public static <SNAPSHOT extends RecordTemplate, ASPECT_UNION extends UnionTemplate, URN extends Urn> SNAPSHOT newSnapshot(
      @Nonnull Class<SNAPSHOT> snapshotClass, @Nonnull URN urn, @Nonnull List<ASPECT_UNION> aspects) {
    return newSnapshot(snapshotClass, urn.toString(), aspects);
  }

  /**
   * Creates a snapshot with its urn field set.
   *
   * @param snapshotClass the type of snapshot to create
   * @param urn value for the urn field as a string
   * @param aspects value for the aspects field
   * @param <SNAPSHOT> must be a valid snapshot model defined in com.linkedin.metadata.snapshot
   * @param <ASPECT_UNION> must be a valid aspect union defined in com.linkedin.metadata.aspect
   * @param <URN> must be a valid URN type
   * @return the created snapshot
   */
  @Nonnull
  public static <SNAPSHOT extends RecordTemplate, ASPECT_UNION extends UnionTemplate, URN extends Urn> SNAPSHOT newSnapshot(
      @Nonnull Class<SNAPSHOT> snapshotClass, @Nonnull String urn, @Nonnull List<ASPECT_UNION> aspects) {

    SnapshotValidator.validateSnapshotSchema(snapshotClass);

    final Class<? extends WrappingArrayTemplate> aspectArrayClass = getAspectsArrayClass(snapshotClass);

    try {
      final SNAPSHOT snapshot = snapshotClass.newInstance();
      if (urn == null) {
        ValidationUtils.throwNullFieldException(URN_FIELD);
      }
      if (aspects == null) {
        ValidationUtils.throwNullFieldException(ASPECTS_FIELD);
      }
      RecordUtils.setRecordTemplatePrimitiveField(snapshot, URN_FIELD, urn);
      WrappingArrayTemplate aspectArray = aspectArrayClass.newInstance();
      aspectArray.addAll(aspects);
      RecordUtils.setRecordTemplateComplexField(snapshot, ASPECTS_FIELD, aspectArray);
      return snapshot;
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @Nonnull
  private static <SNAPSHOT extends RecordTemplate> Class<? extends WrappingArrayTemplate> getAspectsArrayClass(
      @Nonnull Class<SNAPSHOT> snapshotClass) {

    try {
      return snapshotClass.getMethod("getAspects").getReturnType().asSubclass(WrappingArrayTemplate.class);
    } catch (NoSuchMethodException | ClassCastException e) {
      throw new RuntimeException((e));
    }
  }

  /**
   * Creates an aspect union with a specific aspect set.
   *
   * @param aspectUnionClass the type of aspect union to create
   * @param aspect the aspect to set
   * @param <ASPECT_UNION> must be a valid aspect union defined in com.linkedin.metadata.aspect
   * @param <ASPECT> must be a supported aspect type in ASPECT_UNION
   * @return the created aspect union
   */
  @Nonnull
  public static <ASPECT_UNION extends UnionTemplate, ASPECT extends RecordTemplate> ASPECT_UNION newAspectUnion(
      @Nonnull Class<ASPECT_UNION> aspectUnionClass, @Nonnull ASPECT aspect) {

    AspectValidator.validateAspectUnionSchema(aspectUnionClass);

    try {
      ASPECT_UNION aspectUnion = aspectUnionClass.newInstance();
      RecordUtils.setSelectedRecordTemplateInUnion(aspectUnion, aspect);
      return aspectUnion;
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a new {@link AspectVersion}.
   */
  @Nonnull
  public static <ASPECT extends RecordTemplate> AspectVersion newAspectVersion(@Nonnull Class<ASPECT> aspectClass,
      long version) {
    AspectVersion aspectVersion = new AspectVersion();
    aspectVersion.setAspect(ModelUtils.getAspectName(aspectClass));
    aspectVersion.setVersion(version);
    return aspectVersion;
  }

  /**
   * Gets the expected aspect class for a specific kind of snapshot.
   */
  @Nonnull
  public static Class<? extends UnionTemplate> aspectClassForSnapshot(
      @Nonnull Class<? extends RecordTemplate> snapshotClass) {
    SnapshotValidator.validateSnapshotSchema(snapshotClass);

    String aspectClassName = ((TyperefDataSchema) ((ArrayDataSchema) ValidationUtils.getRecordSchema(snapshotClass)
        .getField(ASPECTS_FIELD)
        .getType()).getItems()).getBindingName();

    return getClassFromName(aspectClassName, UnionTemplate.class);
  }

  /**
   * Gets the expected {@link Urn} class for a specific kind of entity.
   */
  @Nonnull
  public static Class<? extends Urn> urnClassForEntity(@Nonnull Class<? extends RecordTemplate> entityClass) {
    EntityValidator.validateEntitySchema(entityClass);
    return urnClassForField(entityClass, URN_FIELD);
  }

  /**
   * Gets the expected {@link Urn} class for a specific kind of snapshot.
   */
  @Nonnull
  public static Class<? extends Urn> urnClassForSnapshot(@Nonnull Class<? extends RecordTemplate> snapshotClass) {
    SnapshotValidator.validateSnapshotSchema(snapshotClass);
    return urnClassForField(snapshotClass, URN_FIELD);
  }

  /**
   * Gets the expected {@link Urn} class for a specific kind of delta.
   */
  @Nonnull
  public static Class<? extends Urn> urnClassForDelta(@Nonnull Class<? extends RecordTemplate> deltaClass) {
    DeltaValidator.validateDeltaSchema(deltaClass);
    return urnClassForField(deltaClass, URN_FIELD);
  }

  /**
   * Gets the expected {@link Urn} class for a specific kind of search document.
   */
  @Nonnull
  public static Class<? extends Urn> urnClassForDocument(@Nonnull Class<? extends RecordTemplate> documentClass) {
    DocumentValidator.validateDocumentSchema(documentClass);
    return urnClassForField(documentClass, URN_FIELD);
  }

  /**
   * Gets the expected {@link Urn} class for a specific kind of relationship.
   */
  @Nonnull
  private static Class<? extends Urn> urnClassForRelationship(
      @Nonnull Class<? extends RecordTemplate> relationshipClass, @Nonnull String fieldName) {
    RelationshipValidator.validateRelationshipSchema(relationshipClass);
    return urnClassForField(relationshipClass, fieldName);
  }

  /**
   * Gets the expected {@link Urn} class for the source field of a specific kind of relationship.
   */
  @Nonnull
  public static Class<? extends Urn> sourceUrnClassForRelationship(
      @Nonnull Class<? extends RecordTemplate> relationshipClass) {
    return urnClassForRelationship(relationshipClass, "source");
  }

  /**
   * Gets the expected {@link Urn} class for the destination field of a specific kind of relationship.
   */
  @Nonnull
  public static Class<? extends Urn> destinationUrnClassForRelationship(
      @Nonnull Class<? extends RecordTemplate> relationshipClass) {
    return urnClassForRelationship(relationshipClass, "destination");
  }

  @Nonnull
  private static Class<? extends Urn> urnClassForField(@Nonnull Class<? extends RecordTemplate> recordClass,
      @Nonnull String fieldName) {
    String urnClassName = ((DataMap) ValidationUtils.getRecordSchema(recordClass)
        .getField(fieldName)
        .getType()
        .getProperties()
        .get("java")).getString("class");

    return getClassFromName(urnClassName, Urn.class);
  }

  /**
   * Validates a specific snapshot-aspect combination.
   */
  public static <SNAPSHOT extends RecordTemplate, ASPECT_UNION extends UnionTemplate> void validateSnapshotAspect(
      @Nonnull Class<SNAPSHOT> snapshotClass, @Nonnull Class<ASPECT_UNION> aspectUnionClass) {
    SnapshotValidator.validateSnapshotSchema(snapshotClass);
    AspectValidator.validateAspectUnionSchema(aspectUnionClass);

    // Make sure that SNAPSHOT's "aspects" array field contains ASPECT_UNION type.
    if (!aspectClassForSnapshot(snapshotClass).equals(aspectUnionClass)) {
      throw new InvalidSchemaException(aspectUnionClass.getCanonicalName() + " is not a supported aspect class of "
          + snapshotClass.getCanonicalName());
    }
  }

  /**
   * Validates a specific snapshot-URN combination.
   */
  public static <SNAPSHOT extends RecordTemplate, URN extends Urn> void validateSnapshotUrn(
      @Nonnull Class<SNAPSHOT> snapshotClass, @Nonnull Class<URN> urnClass) {
    SnapshotValidator.validateSnapshotSchema(snapshotClass);

    // Make sure that SNAPSHOT's "urn" field uses the correct class or subclasses
    if (!urnClassForSnapshot(snapshotClass).isAssignableFrom(urnClass)) {
      throw new InvalidSchemaException(
          urnClass.getCanonicalName() + " is not a supported URN class of " + snapshotClass.getCanonicalName());
    }
  }

  /**
   * Creates a relationship union with a specific relationship set.
   *
   * @param relationshipUnionClass the type of relationship union to create
   * @param relationship the relationship to set
   * @param <RELATIONSHIP_UNION> must be a valid relationship union defined in com.linkedin.metadata.relationship
   * @param <RELATIONSHIP> must be a supported relationship type in ASPECT_UNION
   * @return the created relationship union
   */
  @Nonnull
  public static <RELATIONSHIP_UNION extends UnionTemplate, RELATIONSHIP extends RecordTemplate> RELATIONSHIP_UNION newRelationshipUnion(
      @Nonnull Class<RELATIONSHIP_UNION> relationshipUnionClass, @Nonnull RELATIONSHIP relationship) {

    RelationshipValidator.validateRelationshipUnionSchema(relationshipUnionClass);

    try {
      RELATIONSHIP_UNION relationshipUnion = relationshipUnionClass.newInstance();
      RecordUtils.setSelectedRecordTemplateInUnion(relationshipUnion, relationship);
      return relationshipUnion;
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns all entity classes.
   */
  @Nonnull
  public static Set<Class<? extends RecordTemplate>> getAllEntities() {
    return new Reflections("com.linkedin.metadata.entity").getSubTypesOf(RecordTemplate.class)
        .stream()
        .filter(EntityValidator::isValidEntitySchema)
        .collect(Collectors.toSet());
  }

  /**
   * Get entity type from urn class.
   */
  @Nonnull
  public static String getEntityTypeFromUrnClass(@Nonnull Class<? extends Urn> urnClass) {
    try {
      return urnClass.getDeclaredField("ENTITY_TYPE").get(null).toString();
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Get aspect specific kafka topic name from urn and aspect classes.
   */
  @Nonnull
  public static <URN extends Urn, ASPECT extends RecordTemplate> String getAspectSpecificMAETopicName(@Nonnull URN urn,
      @Nonnull ASPECT newValue) {
    return String.format("%s_%s_%s", METADATA_AUDIT_EVENT_PREFIX, urn.getEntityType().toUpperCase(),
        newValue.getClass().getSimpleName().toUpperCase());
  }

  /**
   * Return true if the aspect is defined in common namespace.
   */
  public static boolean isCommonAspect(@Nonnull Class<? extends RecordTemplate> clazz) {
    return clazz.getPackage().getName().startsWith("com.linkedin.common");
  }

  /**
   * Creates an entity union with a specific entity set.
   *
   * @param entityUnionClass the type of entity union to create
   * @param entity the entity to set
   * @param <ENTITY_UNION> must be a valid enity union defined in com.linkedin.metadata.entity
   * @param <ENTITY> must be a supported entity in entity union
   * @return the created entity union
   */
  @Nonnull
  public static <ENTITY_UNION extends UnionTemplate, ENTITY extends RecordTemplate> ENTITY_UNION newEntityUnion(
      @Nonnull Class<ENTITY_UNION> entityUnionClass, @Nonnull ENTITY entity) {

    EntityValidator.validateEntityUnionSchema(entityUnionClass);

    try {
      ENTITY_UNION entityUnion = entityUnionClass.newInstance();
      RecordUtils.setSelectedRecordTemplateInUnion(entityUnion, entity);
      return entityUnion;
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Get all aspects' class canonical names from a aspect union.
   * @param unionClass the union class contains all the aspects
   * @return A list of aspect canonical names.
   */
  public static <ASPECT_UNION extends UnionTemplate> List<String> getAspectClassNames(Class<ASPECT_UNION> unionClass) {
    try {
      final UnionTemplate unionTemplate = unionClass.newInstance();
      final UnionDataSchema unionDataSchema = (UnionDataSchema) unionTemplate.schema();
      return unionDataSchema.getMembers().stream().map(UnionDataSchema.Member::getUnionMemberKey).collect(Collectors.toList());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Derive the aspect union class from a snapshot class.
   * @param snapshotClass the snapshot class contains the aspect union.
   * @return Aspect union class
   */
  public static <SNAPSHOT extends RecordTemplate, ASPECT_UNION extends UnionTemplate> Class<ASPECT_UNION> getUnionClassFromSnapshot(
      Class<SNAPSHOT> snapshotClass) {
    try {
      Class<?> innerClass = ClassUtils.loadClass(snapshotClass.getMethod("getAspects").getReturnType().getCanonicalName() + "$Fields");
      return (Class<ASPECT_UNION>) innerClass.newInstance().getClass().getMethod("items").getReturnType().getEnclosingClass();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
