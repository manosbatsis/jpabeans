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
package com.github.manosbatsis.scrudbeans.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.MappedSuperclass;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.manosbatsis.scrudbeans.api.domain.BasicAuditedModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.javers.core.metamodel.annotation.DiffIgnore;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Base impl class for generated content
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractBasicAuditedModel extends AbstractSystemUuidPersistableModel implements BasicAuditedModel<String, Serializable> {


    @CreatedDate
    @DiffIgnore
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Date created", readOnly = true)
    @Column(name = "date_created", nullable = false, updatable = false)
    @Getter
    @Setter
    private LocalDateTime createdDate;

    @LastModifiedDate
    @DiffIgnore
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Date last modified", readOnly = true)
    @Column(name = "date_last_modified", nullable = false)
    @Getter
    @Setter
    private LocalDateTime lastModifiedDate;

    @CreatedBy
    @DiffIgnore
    @JsonIgnore
    @Schema(description = "Created by", readOnly = true, hidden = true)
    //TODO @ManyToOne(fetch = FetchType.EAGER  )
    //@TODO JoinColumn(name = "created_by", referencedColumnName = "id", updatable = false)
    @Getter
    @Setter
    private Serializable createdBy;

    @LastModifiedBy
    @DiffIgnore
    @JsonIgnore
    @Schema(description = "udated by", readOnly = true, hidden = true)
    //TODO @ManyToOne(fetch = FetchType.EAGER)
    //TODO @JoinColumn(name = "updated_by", referencedColumnName = "id", updatable = false)
    @Getter
    @Setter
    private Serializable lastModifiedBy;

	public AbstractBasicAuditedModel() {
		super();
	}

}
