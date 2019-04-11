/**
 *
 * ScrudBeans: Model driven development for Spring Boot
 * -------------------------------------------------------------------
 *
 * Copyright © 2005 Manos Batsis (manosbatsis gmail)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.manosbatsis.scrudbeans.jpa.repository;

import static org.springframework.data.jpa.repository.query.QueryUtils.DELETE_ALL_QUERY_STRING;
import static org.springframework.data.jpa.repository.query.QueryUtils.applyAndBind;
import static org.springframework.data.jpa.repository.query.QueryUtils.getQueryString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.persistence.EntityGraph;
import javax.persistence.EntityManager;
import javax.persistence.Subgraph;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.CriteriaUpdate;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.constraints.NotNull;

import com.github.manosbatsis.scrudbeans.api.domain.DisableableModel;
import com.github.manosbatsis.scrudbeans.api.domain.IdModel;
import com.github.manosbatsis.scrudbeans.api.domain.SettableIdModel;
import com.github.manosbatsis.scrudbeans.api.exception.BeanValidationException;
import com.github.manosbatsis.scrudbeans.api.mdd.registry.FieldInfo;
import com.github.manosbatsis.scrudbeans.common.repository.ModelRepository;
import com.github.manosbatsis.scrudbeans.jpa.util.EntityUtil;
import lombok.NonNull;
import org.hibernate.engine.spi.SessionImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.lang.Nullable;
import org.springframework.security.access.method.P;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;


public class BaseRepositoryImpl<T extends IdModel<PK>, PK extends Serializable>
		extends SimpleJpaRepository<T, PK>
		implements ModelRepository<T, PK> {

	private static final Logger LOGGER = LoggerFactory.getLogger(BaseRepositoryImpl.class);

	private boolean skipValidation = false;

	private EntityManager entityManager;

	private JpaEntityInformation<T, ?> entityInformation;

	private Class<T> domainClass;

	protected Validator validator;

	protected final boolean disableableDomainClass;

	/**
	 * Creates a new {@link SimpleJpaRepository} to manage objects of the given {@link JpaEntityInformation}.
	 *
	 * @param entityInformation must not be {@literal null}.
	 * @param entityManager must not be {@literal null}.
	 */
	public BaseRepositoryImpl(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager, Validator validator) {
		super(entityInformation, entityManager);
		LOGGER.debug("new BaseRepositoryImpl, entityInformation: {}, entityManager: {}, validator: {}",
				entityInformation, entityManager, validator);
		Assert.notNull(entityInformation, "BaseRepositoryImpl requires a non-null entityInformation constructor parameter");
		Assert.notNull(entityManager, "BaseRepositoryImpl requires a non-null entityManager constructor parameter");
		this.entityManager = entityManager;
		this.entityInformation = entityInformation;
		this.domainClass = entityInformation.getJavaType();
		this.disableableDomainClass = DisableableModel.class.isAssignableFrom(this.domainClass);
		//Configuration config = ConfigurationFactory.getConfiguration();
		String[] validatorExcludeClasses = {};//TODO config.getStringArray(ConfigurationFactory.VALIDATOR_EXCLUDES_CLASSESS);
		// TODO this.skipValidation = Arrays.asList(validatorExcludeClasses).contains(domainClass.getCanonicalName());
		this.validator = validator;

	}

	/***
	 * {@inheritDoc}
	 */
	@Override
	public Class<T> getDomainClass() {
		return this.domainClass;
	}

	/**
	 * @return the entityManager
	 */
	@Override
	public EntityManager getEntityManager() {
		return this.entityManager;
	}

	/***
	 * {@inheritDoc}
	 */
	@Override
	public <S extends T> S save(S entity) {
		this.validate(entity);
		entity = super.save(entity);
		return entity;
	}

	/***
	 * {@inheritDoc}
	 */
	@Override
	public <S extends T> S saveAndFlush(S entity) {
		this.validate(entity);
		entity = super.saveAndFlush(entity);
		return entity;
	}


	/***
	 * {@inheritDoc}
	 */
	@Override
	public T patch(@P("resource") T delta) {
		// load existing
		T persisted = this.getOne(delta.getId());
		// update it by copying all non-null properties from the given transient instance
		String[] nullPropertyNames = EntityUtil.getNullPropertyNames(delta);
		BeanUtils.copyProperties(delta, persisted, nullPropertyNames);
		// validate
		this.validate(persisted);
		// persist changes
		return this.entityManager.merge(persisted);
	}

	/***
	 * {@inheritDoc}
	 */
	@Override
	public Set<ConstraintViolation<T>> validateConstraints(T resource) {
		LOGGER.debug("validateConstraints, validator: {}, resource: {}", validator, resource);
		Set<ConstraintViolation<T>> constraintViolations = validator.<T>validate(resource);

		return constraintViolations;
	}

	/**
	 * Validate the resource
	 * @param resource
	 */
	protected void validate(T resource) {
		LOGGER.debug("validate resource: {}", resource);
		if (!this.skipValidation) {
			// un-proxy for validation to work
			resource = (T) entityManager.unwrap(SessionImplementor.class).getPersistenceContext().unproxy(resource);
			LOGGER.debug("validate resource after preSave: {}", resource);
			Set<ConstraintViolation<T>> violations = this.validateConstraints(resource);
			LOGGER.debug("validate violations: {}", violations);
			if (!CollectionUtils.isEmpty(violations)) {
				Set<ConstraintViolation> errors = new HashSet<ConstraintViolation>();
				errors.addAll(violations);
				BeanValidationException ex = new BeanValidationException("Validation failed", errors);
				LOGGER.warn("validate, errors: {}", errors);
				ex.setModelType(this.getDomainClass().getCanonicalName());
				throw ex;
			}
		}

	}

	/**
	 *  Used to validate relations
	 * @param id the id of the root model
	 * @param fieldInfo the attribute name of the relationship
	 * @param <RT>
	 * @return
	 */
	@Override
	public <RT extends SettableIdModel> RT findRelatedEntityByOwnId(@NonNull PK id, @NonNull FieldInfo fieldInfo) {
		if (!fieldInfo.getFieldMappingType().isToOne()) {
			throw new IllegalArgumentException("Field " + fieldInfo.getFieldName() + " is not a relation to a single entity");
		}

		CriteriaBuilder cb = this.entityManager.getCriteriaBuilder();
		CriteriaQuery query = cb.createQuery(fieldInfo.getFieldType());

		// if we can match by reverse
		Optional<String> reverseName = fieldInfo.getReverseFieldName();
		if (fieldInfo.isOneToOne() && reverseName.isPresent()) {

			Root root = query.from(fieldInfo.getFieldModelType());
			query.where(cb.equal(root.<T>get(reverseName.get()).get("id"), id));
		}
		// else match by join
		else {

			Root<T> root = query.from(this.domainClass);
			query.where(cb.equal(root.get("id"), id));
			// or maybe:
			//CompoundSelection<Integer> selection = cb.construct(fieldInfo.getFieldType(), fieldInfo.getFieldName());
			Selection selection = root.join(fieldInfo.getFieldName(), JoinType.INNER);
			query.select(selection);
		}

		return (RT) this.entityManager.createQuery(query).getSingleResult();
	}


	private void buildEntityGraph(EntityGraph<T> entityGraph, String[] attributeGraph) {
		List<String> attributePaths = Arrays.asList(attributeGraph);

		// Sort to ensure that the intermediate entity subgraphs are created accordingly.
		Collections.sort(attributePaths);
		Collections.reverse(attributePaths);

		// We build the entity graph based on the paths with highest depth first
		for (String path : attributePaths) {

			// Fast value - just single attribute
			if (!path.contains(".")) {
				entityGraph.addAttributeNodes(path);
				continue;
			}

			// We need to build nested sub fetch graphs
			String[] pathComponents = StringUtils.delimitedListToStringArray(path, ".");
			Subgraph<?> parent = null;

			for (int c = 0; c < pathComponents.length - 1; c++) {
				parent = c == 0 ? entityGraph.addSubgraph(pathComponents[c]) : parent.addSubgraph(pathComponents[c]);
			}

			parent.addAttributeNodes(pathComponents[pathComponents.length - 1]);
		}
	}

	/**
	 * Creates a {@link TypedQuery} for the given {@link Specification} and {@link Sort}.
	 *
	 * @param spec can be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 * @param sort must not be {@literal null}.
	 * @return
	 */
	@Override
	protected <S extends T> TypedQuery<S> getQuery(@Nullable Specification<S> spec, Class<S> domainClass, Sort sort) {
		return super.getQuery(applyDisabledFilter(spec), domainClass, sort);
	}


	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.io.Serializable)
	 */
	@Override
	@Transactional
	public void deleteById(PK id) {
		if (this.disableableDomainClass) {
			this.softDelete(id);
		}
		else super.deleteById(id);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.lang.Object)
	 */
	@Override
	@Transactional
	public void delete(T entity) {
		if (this.disableableDomainClass) {
			this.softDelete(entity.getId());
		}
		else super.delete(entity);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.lang.Iterable)
	 */
	@Override
	@Transactional
	public void deleteAll(@NotNull Iterable<? extends T> entities) {
		if (this.disableableDomainClass) {
			for (T entity : entities) {
				this.softDelete(entity.getId());
			}
		}
		else super.deleteAll(entities);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#deleteInBatch(java.lang.Iterable)
	 */
	@Override
	@Transactional
	public void deleteInBatch(Iterable<T> entities) {
		if (this.disableableDomainClass) {
			if (!entities.iterator().hasNext()) {
				return;
			}
			applyAndBind(
					getQueryString(SOFT_DELETE_ALL_QUERY_STRING, entityInformation.getEntityName()),
					entities, entityManager
			).executeUpdate();
		}
		else super.deleteInBatch(entities);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.Repository#deleteAll()
	 */
	@Override
	@Transactional
	public void deleteAll() {
		for (T element : findAll()) {
			delete(element);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#deleteAllInBatch()
	 */
	@Override
	@Transactional
	public void deleteAllInBatch() {
		entityManager.createQuery(
				getQueryString(DELETE_ALL_QUERY_STRING, entityInformation.getEntityName()))
				.executeUpdate();
	}

	/**
	 * Creates a new count query for the given {@link Specification}.
	 *
	 * @param spec can be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 * @return
	 */
	@Override
	protected <S extends T> TypedQuery<Long> getCountQuery(@Nullable Specification<S> spec, Class<S> domainClass) {
		return super.getCountQuery(applyDisabledFilter(spec), domainClass);
	}


	/** Applies a soft-delete filter */
	private <S extends T> Specification<S> applyDisabledFilter(@Nullable Specification<S> spec) {
		if (this.disableableDomainClass) {
			if (spec == null) spec = notDisabled();
			else spec = spec.and(notDisabled());
		}
		return spec;
	}

	/** Performs a soft-delete  */
	protected void softDelete(PK id) {
		CriteriaBuilder cb = this.entityManager.getCriteriaBuilder();
		// create update
		CriteriaUpdate<T> update = cb.createCriteriaUpdate(this.domainClass);
		// set the root class
		Root e = update.from(this.domainClass);
		// set update and where clause
		update.set("disabled", LocalDateTime.now());
		update.where(cb.equal(e.get("id"), id));
		// perform update
		this.entityManager.createQuery(update).executeUpdate();
	}

	private static final String FIELD_DISABLED = "disabled";

	public static final String SOFT_DELETE_ALL_QUERY_STRING = "update %s x set disabled = NOW()";

	private static final class DisabledIsNull<T> implements Specification<T> {
		@Override
		public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
			return cb.isNull(root.<LocalDateTime>get(FIELD_DISABLED));
		}
	}

	private static final class ScheduledToBeDisabled<T> implements Specification<T> {
		@Override
		public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
			return cb.greaterThan(root.get(FIELD_DISABLED), LocalDateTime.now());
		}
	}

	private static final <T> Specification<T> notDisabled() {
		return Specification.where(new DisabledIsNull<T>()).or(new ScheduledToBeDisabled<T>());
	}


}
