package si.opkp.model;

import com.moybl.restql.Token;
import com.moybl.restql.ast.AstNode;
import com.moybl.restql.factory.RestQLBuilder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

import si.opkp.query.SelectOperation;
import si.opkp.query.mysql.MySQLSelectOperationBuilder;
import si.opkp.util.Graph;
import si.opkp.util.Pojo;
import si.opkp.util.RequestField;

@Component
public class MySQLDatabase implements Database {

	private Graph<String, AstNode> dataGraph;
	private Map<String, NodeDefinition> definitions;
	private Map<String, FunctionDefinition> functions;
	private Connection connection;

	@Autowired
	public void setDataSource(DataSource dataSource) throws Exception {
		connection = dataSource.getConnection();
		DatabaseMetaData meta = connection.getMetaData();
		definitions = new HashMap<>();
		functions = new LinkedHashMap<>();
		dataGraph = new Graph<>();

		ResultSet tables = meta.getTables(null, meta.getUserName(), "%", null);

		while (tables.next()) {
			String tableName = tables.getString("TABLE_NAME")
											 .toLowerCase();

			// ignore 'sqlite*'
			if (tableName.startsWith("sqlite")) {
				continue;
			}

			dataGraph.addNode(tableName);

			Map<String, FieldDefinition> fields = new HashMap<>();
			Set<FieldDefinition> primaryKeys = new HashSet<>();
			Set<String> primaryKeyNames = new HashSet<>();

			ResultSet pks = meta.getPrimaryKeys(null, meta.getUserName(), tableName);
			ResultSet cols = meta.getColumns(null, meta.getUserName(), tableName, "%");

			while (pks.next()) {
				primaryKeyNames.add(pks.getString("COLUMN_NAME"));
			}

			while (cols.next()) {
				String name = cols.getString("COLUMN_NAME");
				FieldType type = FieldType.fromSQLType(cols.getInt("DATA_TYPE"));
				boolean key = false;
				boolean notNull = cols.getInt("NULLABLE") != DatabaseMetaData.columnNullable;

				if (primaryKeyNames.contains(name)) {
					key = true;
				}

				//Object defaultValue = cols.getString("COLUMN_DEF");
				Object defaultValue = null;

				FieldDefinition fd = new FieldDefinition(name, tableName, type, notNull, key, defaultValue);
				fields.put(name, fd);

				if (key) {
					primaryKeys.add(fd);
				}
			}

			definitions.put(tableName, new NodeDefinition(tableName, fields, primaryKeys));
		}

		// set graph's edges based on foreign keys
		for (String tableName : dataGraph.getNodes()) {
			ResultSet fks = meta.getExportedKeys(null, meta.getUserName(), tableName);

			while (fks.next()) {
				String pk = fks.getString("PKCOLUMN_NAME");
				String fk = fks.getString("FKCOLUMN_NAME");
				NodeDefinition thisTable = definitions.get(tableName);
				NodeDefinition otherTable = definitions.get(fks.getString("FKTABLE_NAME"));

				RestQLBuilder b = new RestQLBuilder();
				AstNode connection = b.binaryOperation(b.member(b.identifier(tableName), b.identifier(pk)),
						Token.EQUAL,
						b.member(b.identifier(otherTable.getName()), b.identifier(fk)));
				dataGraph.addEdge(tableName, otherTable.getName(), connection);
			}
		}

		// set functions
		ResultSet funcs = meta.getFunctions(null, meta.getUserName(), "%");

		while (funcs.next()) {
			String name = funcs.getString("FUNCTION_NAME");
			List<ParameterDefinition> parameters = new ArrayList<>();

			ResultSet funcCols = meta.getFunctionColumns(null, meta.getUserName(), name, "%");

			while (funcCols.next()) {
				String pName = funcCols.getString("COLUMN_NAME");
				FieldType pType = FieldType.fromSQLType(funcCols.getInt("COLUMN_TYPE"));

				parameters.add(new ParameterDefinition(pName, pType));
			}

			functions.put(name, new FunctionDefinition(name, parameters));
		}

		// set procedures
		ResultSet procs = meta.getProcedures(null, meta.getUserName(), "%");

		while (procs.next()) {
			String name = procs.getString("PROCEDURE_NAME");
			List<ParameterDefinition> parameters = new ArrayList<>();

			ResultSet procCols = meta.getProcedureColumns(null, meta.getUserName(), name, "%");

			while (procCols.next()) {
				String pName = procCols.getString("COLUMN_NAME");
				FieldType pType = FieldType.fromSQLType(procCols.getInt("DATA_TYPE"));

				parameters.add(new ParameterDefinition(pName, pType));
			}

			functions.put(name, new FunctionDefinition(name, parameters));
		}
/*
		Map<String, Set<String>> neighbours = new HashMap<>();
		Map<String, List<Pojo>> fields = new HashMap<>();

		dataGraph.getNodes()
					.forEach(node -> {
						neighbours.put(node, dataGraph.getNeighbours(node));

						List<Pojo> nodeFields = new ArrayList<>();

						for (FieldDefinition fd : definitions.get(node)
																		 .getFields()
																		 .values()) {
							Pojo field = new Pojo();
							field.setProperty("name", fd.getName());
							field.setProperty("notNull", fd.isNotNull());

							switch (fd.getType()) {
								case DATETIME:
									field.setProperty("type", "date");
									break;
								case DECIMAL:
								case INTEGER:
									field.setProperty("type", "number");
									break;
								case STRING:
									field.setProperty("type", "string");
									break;
							}

							nodeFields.add(field);
						}

						fields.put(node, nodeFields);
					});

		System.out.println(Util.prettyJson(fields));
		System.out.println(Util.prettyJson(neighbours));
		*/
	}

	@Override
	public Graph<String, AstNode> getDataGraph() {
		return dataGraph;
	}

	@Override
	public Map<String, NodeDefinition> getNodes() {
		return definitions;
	}

	@Override
	public Map<String, FunctionDefinition> getFunctions() {
		return functions;
	}

	@Override
	public NodeResult query(SelectOperation selectOperation) {
		List<RequestField> fields = flattenFieldList(selectOperation.getFields());
		List<RequestField> edgeFields = new ArrayList<>();
		Iterator<RequestField> fieldIterator = fields.iterator();

		while (fieldIterator.hasNext()) {
			RequestField rf = fieldIterator.next();

			if (rf.isEdge()) {
				String from = selectOperation.getFrom();

				if (rf.getParent() != null) {
					from = rf.getParent()
								.getName();
				}

				Optional<Graph<String, AstNode>.Edge> edge = dataGraph.getEdge(from, rf.getName());

				if (!edge.isPresent()) {
					return new NodeErrorResult("Cannot join '" + from + "' and '" + rf.getName() + "'", HttpStatus.BAD_REQUEST);
				}

				selectOperation.join(edge.get());

				edgeFields.add(rf);
				fieldIterator.remove();
			}
		}

		addDefaultFields(selectOperation.getFrom(), fields);

		for (Graph<String, AstNode>.Edge edge : selectOperation.getJoins()) {
			addDefaultFields(edge.getNodeB(), fields);
		}

		selectOperation.fields(fields);

		try {
			String stmt = new MySQLSelectOperationBuilder().build(selectOperation);

			ResultSet rs = connection.createStatement()
											 .executeQuery(stmt);

			ResultSetMetaData meta = rs.getMetaData();
			List<Pojo> rows = new ArrayList<>();

			while (rs.next()) {
				Pojo obj = new Pojo();

				for (int i = 1; i <= meta.getColumnCount(); i++) {
					obj.setProperty(meta.getColumnLabel(i), rs.getObject(i));
				}

				rows.add(obj);
			}

			for (RequestField field : edgeFields) {
				createEdgeGroup(selectOperation.getFrom(), field, rows);
			}

			return new NodeSuccessResult(rows, -1);
		} catch (SQLException e) {
			return new NodeErrorResult(e.getMessage());
		}
	}

	private void createEdgeGroup(String node, RequestField field, List<Pojo> rows) {
		Map<Object, List<Pojo>> groups = new HashMap<>();
		Map<Object, Pojo> groupedObjects = new HashMap<>();
		FieldDefinition groupBy = definitions.get(node)
														 .getIdentifiers()
														 .iterator()
														 .next();


		for (Pojo row : rows) {
			Object id = row.getProperty(groupBy.getNode() + "." + groupBy.getName());

			if (!groups.containsKey(id)) {
				groups.put(id, new ArrayList<>());

				Pojo pojo = new Pojo();

				row.getProperties()
					.entrySet()
					.forEach(prop -> {
						if (prop.getKey()
								  .startsWith(node)) {
							String propName = prop.getKey()
														 .substring(prop.getKey()
																			 .indexOf('.') + 1);
							pojo.setProperty(propName, prop.getValue());
						}
					});

				groupedObjects.put(id, pojo);
			}

			Pojo edgeObject = new Pojo();

			row.getProperties()
				.entrySet()
				.forEach(prop -> {
					if (prop.getKey()
							  .startsWith(field.getName())) {
						String propName = prop.getKey()
													 .substring(prop.getKey()
																		 .indexOf('.') + 1);
						edgeObject.setProperty(propName, prop.getValue());
					}
				});

			groups.get(id)
					.add(edgeObject);
		}

		rows.clear();

		// populate edges
		for (Map.Entry<Object, Pojo> entry : groupedObjects.entrySet()) {
			List<Pojo> edge = groups.get(entry.getKey());
			Pojo obj = entry.getValue();

			obj.setProperty(field.getName(), edge);

			rows.add(obj);
		}
	}

	private List<RequestField> flattenFieldList(List<RequestField> fields) {
		List<RequestField> result = new ArrayList<>();
		Stack<List<RequestField>> stack = new Stack<>();

		stack.add(fields);

		while (!stack.isEmpty()) {
			List<RequestField> list = stack.pop();

			for (RequestField field : list) {
				if (field.isEdge()) {
					stack.add(field.getNestedFields());
				}

				result.add(field);
			}
		}

		return result;
	}

	private void addDefaultFields(String node, List<RequestField> fields) {
		definitions.get(node)
					  .getIdentifiers()
					  .forEach(id -> {
						  if (!fields.stream()
										 .anyMatch(rf -> rf.getName()
																 .equals(id.getName()))) {
							  fields.add(new RequestField(id.getName(), id.getNode()));
						  }
					  });
	}

	@Override
	public NodeResult callFunction(String function, Object... params) {
		StringBuilder stmt = new StringBuilder();
		List<ParameterDefinition> pd = functions.get(function)
															 .getParameters();

		stmt.append("CALL ")
			 .append(function)
			 .append("(");
		for (int i = 0; i < params.length; i++) {
			switch (pd.get(i)
						 .getType()) {
				case DATETIME:
					break;
				case INTEGER:
					stmt.append(params[i].toString()
												.replaceAll("\\.[0-9]*$", ""));
					break;
				case DECIMAL:
					stmt.append(params[i]);
					break;
				case STRING:
					stmt.append("'")
						 .append(params[i])
						 .append("'");
					break;
			}

			if (i < params.length - 1) {
				stmt.append(", ");
			}
		}
		stmt.append(")");

		try {
			ResultSet rs = connection.createStatement()
											 .executeQuery(stmt.toString());

			ResultSetMetaData meta = rs.getMetaData();
			List<Pojo> objects = new ArrayList<>();

			while (rs.next()) {
				Pojo obj = new Pojo();

				for (int i = 1; i <= meta.getColumnCount(); i++) {
					obj.setProperty(meta.getColumnName(i), rs.getObject(i));
				}

				objects.add(obj);
			}

			return new NodeSuccessResult(objects, 0);
		} catch (SQLException e) {
			return new NodeErrorResult(e.getMessage());
		}
	}

}
