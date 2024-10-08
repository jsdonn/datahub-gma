package com.linkedin.metadata.dao.internal;

import com.linkedin.common.urn.Urn;
import com.linkedin.metadata.dao.BaseQueryDAO;
import com.linkedin.metadata.dao.Neo4jQueryDAO;
import com.linkedin.metadata.dao.Neo4jTestServerBuilder;
import com.linkedin.metadata.query.Criterion;
import com.linkedin.metadata.query.CriterionArray;
import com.linkedin.metadata.query.Filter;
import com.linkedin.testing.RelationshipFoo;
import com.linkedin.testing.EntityFoo;
import com.linkedin.testing.EntityBar;
import com.linkedin.testing.TestUtils;
import com.linkedin.testing.urn.BarUrn;
import com.linkedin.testing.urn.FooUrn;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.linkedin.metadata.dao.Neo4jUtil.*;
import static com.linkedin.metadata.dao.internal.BaseGraphWriterDAO.RemovalOption.*;
import static com.linkedin.testing.TestUtils.*;
import static org.testng.Assert.*;


public class Neo4jGraphWriterDAOTest {

  private Neo4jTestServerBuilder _serverBuilder;
  private Neo4jGraphWriterDAO _dao;
  private Neo4jTestHelper _helper;
  private BaseQueryDAO _queryDao;
  private TestMetricListener _testMetricListener;

  private static class TestMetricListener implements Neo4jGraphWriterDAO.MetricListener {
    int entitiesAdded = 0;
    int entityAddedEvents = 0;
    int entitiesRemoved = 0;
    int entityRemovedEvents = 0;
    int relationshipsAdded = 0;
    int relationshipAddedEvents = 0;
    int relationshipsRemoved = 0;
    int relationshipRemovedEvents = 0;

    @Override
    public void onEntitiesAdded(int entityCount, long updateTimeMs, int retries) {
      entityAddedEvents++;
      entitiesAdded += entityCount;
    }

    @Override
    public void onRelationshipsAdded(int relationshipCount, long updateTimeMs, int retries) {
      relationshipAddedEvents++;
      relationshipsAdded += relationshipCount;
    }

    @Override
    public void onEntitiesRemoved(int entityCount, long updateTimeMs, int retries) {
      entityRemovedEvents++;
      entitiesRemoved += entityCount;
    }

    @Override
    public void onRelationshipsRemoved(int relationshipCount, long updateTimeMs, int retries) {
      relationshipRemovedEvents++;
      relationshipsRemoved += relationshipCount;
    }
  }

  @BeforeMethod
  public void init() {
    _serverBuilder = new Neo4jTestServerBuilder();
    _serverBuilder.newServer();
    _testMetricListener = new TestMetricListener();
    final Driver driver = GraphDatabase.driver(_serverBuilder.boltURI());
    _dao = new Neo4jGraphWriterDAO(driver, TestUtils.getAllTestEntities());
    _helper = new Neo4jTestHelper(driver, TestUtils.getAllTestEntities());
    _queryDao = new Neo4jQueryDAO(driver);
    _dao.addMetricListener(_testMetricListener);
  }

  @AfterMethod
  public void tearDown() {
    _serverBuilder.shutdown();
  }

  @Test
  public void testAddRemoveEntity() throws Exception {
    FooUrn urn = makeFooUrn(1);
    EntityFoo entity = new EntityFoo().setUrn(urn).setValue("foo");

    _dao.addEntity(entity);
    Optional<Map<String, Object>> node = _helper.getNode(urn);
    assertEntityFoo(node.get(), entity);
    assertEquals(_testMetricListener.entitiesAdded, 1);
    assertEquals(_testMetricListener.entityAddedEvents, 1);

    _dao.removeEntity(urn);
    node = _helper.getNode(urn);
    assertFalse(node.isPresent());
    assertEquals(_testMetricListener.entitiesRemoved, 1);
    assertEquals(_testMetricListener.entityRemovedEvents, 1);
  }

  @Test
  public void testPartialUpdateEntity() throws Exception {
    FooUrn urn = makeFooUrn(1);
    EntityFoo entity = new EntityFoo().setUrn(urn);

    _dao.addEntity(entity);
    Optional<Map<String, Object>> node = _helper.getNode(urn);
    assertEntityFoo(node.get(), entity);

    // add value for optional field
    EntityFoo entity2 = new EntityFoo().setUrn(urn).setValue("IamTheSameEntity");
    _dao.addEntity(entity2);
    node = _helper.getNode(urn);
    assertEquals(_helper.getAllNodes(urn).size(), 1);
    assertEntityFoo(node.get(), entity2);

    // change value for optional field
    EntityFoo entity3 = new EntityFoo().setUrn(urn).setValue("ChangeValue");
    _dao.addEntity(entity3);
    node = _helper.getNode(urn);
    assertEquals(_helper.getAllNodes(urn).size(), 1);
    assertEntityFoo(node.get(), entity3);
  }

  @Test
  public void testAddRemoveEntities() throws Exception {
    EntityFoo entity1 = new EntityFoo().setUrn(makeFooUrn(1)).setValue("foo");
    EntityFoo entity2 = new EntityFoo().setUrn(makeFooUrn(2)).setValue("bar");
    EntityFoo entity3 = new EntityFoo().setUrn(makeFooUrn(3)).setValue("baz");
    List<EntityFoo> entities = Arrays.asList(entity1, entity2, entity3);

    _dao.addEntities(entities);
    assertEntityFoo(_helper.getNode(entity1.getUrn()).get(), entity1);
    assertEntityFoo(_helper.getNode(entity2.getUrn()).get(), entity2);
    assertEntityFoo(_helper.getNode(entity3.getUrn()).get(), entity3);
    assertEquals(_testMetricListener.entitiesAdded, 3);
    assertEquals(_testMetricListener.entityAddedEvents, 1);

    _dao.removeEntities(Arrays.asList(entity1.getUrn(), entity3.getUrn()));
    assertFalse(_helper.getNode(entity1.getUrn()).isPresent());
    assertTrue(_helper.getNode(entity2.getUrn()).isPresent());
    assertFalse(_helper.getNode(entity3.getUrn()).isPresent());
    assertEquals(_testMetricListener.entitiesRemoved, 2);
    assertEquals(_testMetricListener.entityRemovedEvents, 1);
  }

  @Test
  public void testAddRelationshipNodeNonExist() throws Exception {
    FooUrn urn1 = makeFooUrn(1);
    BarUrn urn2 = makeBarUrn(2);
    RelationshipFoo relationship = new RelationshipFoo().setSource(urn1).setDestination(urn2);

    _dao.addRelationship(relationship, REMOVE_NONE, false);

    assertRelationshipFoo(_helper.getEdges(relationship), 1);
    assertEntityFoo(_helper.getNode(urn1).get(), new EntityFoo().setUrn(urn1));
    assertEntityBar(_helper.getNode(urn2).get(), new EntityBar().setUrn(urn2));
    assertEquals(_testMetricListener.relationshipsAdded, 1);
    assertEquals(_testMetricListener.relationshipAddedEvents, 1);
  }

  @Test
  public void testPartialUpdateEntityCreatedByRelationship() throws Exception {
    FooUrn urn1 = makeFooUrn(1);
    FooUrn urn2 = makeFooUrn(2);
    RelationshipFoo relationship = new RelationshipFoo().setSource(urn1).setDestination(urn2);

    _dao.addRelationship(relationship, REMOVE_NONE, false);

    // Check if adding an entity with same urn and with label creates a new node
    _dao.addEntity(new EntityFoo().setUrn(urn1));
    assertEquals(_helper.getAllNodes(urn1).size(), 1);
  }

  @Test
  public void testAddRemoveRelationships() throws Exception {
    // Add entity1
    FooUrn urn1 = makeFooUrn(1);
    EntityFoo entity1 = new EntityFoo().setUrn(urn1).setValue("foo");
    _dao.addEntity(entity1);
    assertEntityFoo(_helper.getNode(urn1).get(), entity1);

    // Add entity2
    BarUrn urn2 = makeBarUrn(2);
    EntityBar entity2 = new EntityBar().setUrn(urn2).setValue("bar");
    _dao.addEntity(entity2);
    assertEntityBar(_helper.getNode(urn2).get(), entity2);

    // add relationship1 (urn1 -> urn2)
    RelationshipFoo relationship1 = new RelationshipFoo().setSource(urn1).setDestination(urn2);
    _dao.addRelationship(relationship1, REMOVE_NONE, false);
    assertRelationshipFoo(_helper.getEdges(relationship1), 1);

    // add relationship1 again
    _dao.addRelationship(relationship1, false);
    assertRelationshipFoo(_helper.getEdges(relationship1), 1);

    // add relationship2 (urn1 -> urn3)
    Urn urn3 = makeUrn(3);
    RelationshipFoo relationship2 = new RelationshipFoo().setSource(urn1).setDestination(urn3);
    _dao.addRelationship(relationship2, false);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn1, RelationshipFoo.class), 2);

    // remove relationship1
    _dao.removeRelationship(relationship1);
    assertRelationshipFoo(_helper.getEdges(relationship1), 0);

    // remove relationship1 & relationship2
    _dao.removeRelationships(Arrays.asList(relationship1, relationship2));
    assertRelationshipFoo(_helper.getEdgesFromSource(urn1, RelationshipFoo.class), 0);


    assertEquals(_testMetricListener.relationshipsAdded, 3);
    assertEquals(_testMetricListener.relationshipAddedEvents, 3);

    assertEquals(_testMetricListener.relationshipsRemoved, 3);
    assertEquals(_testMetricListener.relationshipRemovedEvents, 2);
  }

  @Test
  public void testAddRelationshipRemoveAll() throws Exception {
    // Add entity1
    FooUrn urn1 = makeFooUrn(1);
    EntityFoo entity1 = new EntityFoo().setUrn(urn1).setValue("foo");
    _dao.addEntity(entity1);
    assertEntityFoo(_helper.getNode(urn1).get(), entity1);

    // Add entity2
    BarUrn urn2 = makeBarUrn(2);
    EntityBar entity2 = new EntityBar().setUrn(urn2).setValue("bar");
    _dao.addEntity(entity2);
    assertEntityBar(_helper.getNode(urn2).get(), entity2);

    // add relationship1 (urn1 -> urn2)
    RelationshipFoo relationship1 = new RelationshipFoo().setSource(urn1).setDestination(urn2);
    _dao.addRelationship(relationship1, REMOVE_NONE, false);
    assertRelationshipFoo(_helper.getEdges(relationship1), 1);

    // add relationship2 (urn1 -> urn3), removeAll from source
    Urn urn3 = makeUrn(3);
    RelationshipFoo relationship2 = new RelationshipFoo().setSource(urn1).setDestination(urn3);
    _dao.addRelationship(relationship2, REMOVE_ALL_EDGES_FROM_SOURCE, false);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn1, RelationshipFoo.class), 1);

    // add relationship3 (urn4 -> urn3), removeAll from destination
    Urn urn4 = makeUrn(4);
    RelationshipFoo relationship3 = new RelationshipFoo().setSource(urn4).setDestination(urn3);
    _dao.addRelationship(relationship3, REMOVE_ALL_EDGES_TO_DESTINATION, false);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn1, RelationshipFoo.class), 0);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn4, RelationshipFoo.class), 1);

    // add relationship3 again without removal
    _dao.addRelationship(relationship3, false);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn4, RelationshipFoo.class), 1);

    // add relationship3 again, removeAll from source & destination
    _dao.addRelationship(relationship3, REMOVE_ALL_EDGES_FROM_SOURCE_TO_DESTINATION, false);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn1, RelationshipFoo.class), 0);
    assertRelationshipFoo(_helper.getEdgesFromSource(urn4, RelationshipFoo.class), 1);
  }

  @Test
  public void upsertNodeAddNewProperty() throws Exception {
    // given
    final FooUrn urn = makeFooUrn(1);
    final EntityFoo initialEntity = new EntityFoo().setUrn(urn);
    final EntityFoo updatedEntity = new EntityFoo().setUrn(urn).setValue("updated");

    // when
    _dao.addEntity(initialEntity);
    _dao.addEntity(updatedEntity);

    // then
    assertEntityFoo(_helper.getNode(urn).get(), updatedEntity);
  }

  @Test
  public void upsertEdgeAddNewProperty() throws Exception {
    // given
    final EntityFoo foo = new EntityFoo().setUrn(makeFooUrn(1));
    final EntityBar bar = new EntityBar().setUrn(makeBarUrn(2)).setValue("bar");
    _dao.addEntity(foo);
    _dao.addEntity(bar);

    final RelationshipFoo initialRelationship =
        new RelationshipFoo().setSource(foo.getUrn()).setDestination(bar.getUrn());
    final RelationshipFoo updatedRelationship =
        new RelationshipFoo().setSource(foo.getUrn()).setDestination(bar.getUrn()).setType("test");
    _dao.addRelationship(initialRelationship, false);

    // when
    _dao.addRelationship(updatedRelationship, false);

    // then
    assertEquals(_queryDao.findRelationships(EntityFoo.class,
        new Filter().setCriteria(new CriterionArray(new Criterion().setField("urn").setValue(foo.getUrn().toString()))),
        EntityBar.class,
        new Filter().setCriteria(new CriterionArray(new Criterion().setField("urn").setValue(bar.getUrn().toString()))),
        RelationshipFoo.class, new Filter().setCriteria(new CriterionArray()), 0, 10),
        Collections.singletonList(updatedRelationship));
  }

  @Test
  public void upsertNodeChangeProperty() throws Exception {
    // given
    final FooUrn urn = makeFooUrn(1);
    final EntityFoo initialEntity = new EntityFoo().setUrn(urn).setValue("before");
    final EntityFoo updatedEntity = new EntityFoo().setUrn(urn).setValue("after");
    _dao.addEntity(initialEntity);

    // when
    _dao.addEntity(updatedEntity);

    // then
    assertEntityFoo(_helper.getNode(urn).get(), updatedEntity);
  }

  @Test
  public void upsertEdgeChangeProperty() throws Exception {
    // given
    final EntityFoo foo = new EntityFoo().setUrn(makeFooUrn(1));
    final EntityBar bar = new EntityBar().setUrn(makeBarUrn(2)).setValue("bar");
    _dao.addEntity(foo);
    _dao.addEntity(bar);

    final RelationshipFoo initialRelationship =
        new RelationshipFoo().setSource(foo.getUrn()).setDestination(bar.getUrn()).setType("before");
    final RelationshipFoo updatedRelationship =
        new RelationshipFoo().setSource(foo.getUrn()).setDestination(bar.getUrn()).setType("after");
    _dao.addRelationship(initialRelationship, false);

    // when
    _dao.addRelationship(updatedRelationship, false);

    // then
    assertEquals(_queryDao.findRelationships(EntityFoo.class,
        new Filter().setCriteria(new CriterionArray(new Criterion().setField("urn").setValue(foo.getUrn().toString()))),
        EntityBar.class,
        new Filter().setCriteria(new CriterionArray(new Criterion().setField("urn").setValue(bar.getUrn().toString()))),
        RelationshipFoo.class, new Filter().setCriteria(new CriterionArray()), 0, 10),
        Collections.singletonList(updatedRelationship));
  }

  @Test
  public void upsertNodeRemovedProperty() throws Exception {
    // given
    final FooUrn urn = makeFooUrn(1);
    final EntityFoo initialEntity = new EntityFoo().setUrn(urn).setValue("before");
    final EntityFoo updatedEntity = new EntityFoo().setUrn(urn);
    _dao.addEntity(initialEntity);

    // when
    _dao.addEntity(updatedEntity);

    // then
    // Upsert won't ever delete properties.
    assertEntityFoo(_helper.getNode(urn).get(), initialEntity);
  }

  @Test
  public void upsertEdgeRemoveProperty() throws Exception {
    // given
    final EntityFoo foo = new EntityFoo().setUrn(makeFooUrn(1));
    final EntityBar bar = new EntityBar().setUrn(makeBarUrn(2)).setValue("bar");
    _dao.addEntity(foo);
    _dao.addEntity(bar);

    final RelationshipFoo initialRelationship =
        new RelationshipFoo().setSource(foo.getUrn()).setDestination(bar.getUrn()).setType("before");
    final RelationshipFoo updatedRelationship =
        new RelationshipFoo().setSource(foo.getUrn()).setDestination(bar.getUrn());
    _dao.addRelationship(initialRelationship, false);

    // when
    _dao.addRelationship(updatedRelationship, false);

    // then
    assertEquals(_queryDao.findRelationships(EntityFoo.class,
        new Filter().setCriteria(new CriterionArray(new Criterion().setField("urn").setValue(foo.getUrn().toString()))),
        EntityBar.class,
        new Filter().setCriteria(new CriterionArray(new Criterion().setField("urn").setValue(bar.getUrn().toString()))),
        RelationshipFoo.class, new Filter().setCriteria(new CriterionArray()), 0, 10),
        // Upsert won't ever delete properties.
        Collections.singletonList(initialRelationship));
  }

  private void assertEntityFoo(@Nonnull Map<String, Object> node, @Nonnull EntityFoo entity) {
    assertEquals(node.get("urn"), entity.getUrn().toString());
    assertEquals(node.get("value"), entity.getValue());
  }

  private void assertEntityBar(@Nonnull Map<String, Object> node, @Nonnull EntityBar entity) {
    assertEquals(node.get("urn"), entity.getUrn().toString());
    assertEquals(node.get("value"), entity.getValue());
  }

  private void assertRelationshipFoo(@Nonnull List<Map<String, Object>> edges, int count) {
    assertEquals(edges.size(), count);
    edges.forEach(edge -> assertTrue(edge.isEmpty()));
  }
}
