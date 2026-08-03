/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *
 */

import React from 'react';
import { useSelector } from 'react-redux';
import { makeStyles } from '@material-ui/core/styles';
import Paper from '@material-ui/core/Paper';
import Grid from '@material-ui/core/Grid';
import Box from '@material-ui/core/Box';
import { Table, TableCell, TableRow, TableBody } from '@material-ui/core';
import HeadingSection from './commons/HeadingSection'
import SourceViewSection from './commons/SourceViewSection'
import Typography from '@material-ui/core/Typography';
import CircularProgress from '@material-ui/core/CircularProgress';
import EnabledIcon from '@material-ui/icons/CheckCircleOutlineOutlined';
import DisabledIcon from '@material-ui/icons/BlockOutlined';
import ExpansionPanel from '@material-ui/core/ExpansionPanel';
import ExpansionPanelSummary from '@material-ui/core/ExpansionPanelSummary';
import ExpansionPanelDetails from '@material-ui/core/ExpansionPanelDetails';
import ExpandMoreIcon from '@material-ui/icons/ExpandMore';
import HTTPClient from '../../utils/HTTPClient';

export default function DataServicesSideDrawer(props) {
    const globalGroupId = useSelector(state => state.groupId);
    var nodeData = props.nodeData;
    const isFaulty = nodeData.details.status === 'faulty';
    const artifactName = nodeData.details.serviceName || nodeData.details.name;
    const nodeId = nodeData.nodeId;
    const classes = useStyles();

    if (isFaulty) {
        return (
            <div className={classes.root}>
                <Grid container spacing={3}>
                    <Grid item xs={12}>
                        <HeadingSection name={artifactName} nodeId={nodeId} />
                        <Paper className={classes.paper} elevation={0} square>
                            <FaultyDataServiceDetailTable nodeData={nodeData.details} />
                        </Paper>
                        <Box pl={2} pr={2} pt={1}>
                            <StackTraceSection
                                groupId={globalGroupId}
                                nodeId={nodeId}
                                serviceName={artifactName}
                            />
                        </Box>
                    </Grid>
                </Grid>
            </div>
        );
    }

    return (
        <div className={classes.root}>
            <Grid container spacing={3}>
                <Grid item xs={12}>
                    <HeadingSection name={artifactName} nodeId={nodeId} />
                    <SourceViewSection
                        artifactType="data-services"
                        artifactName={artifactName}
                        nodeId={nodeId}
                        designContent={<>
                            <Paper className={classes.paper} elevation={0} square>
                                <DataServicesDetailTable nodeData={nodeData.details} />
                            </Paper>
                            <Box pl={2}>
                                <DataSourcesSection dataSources={nodeData.details.dataSources} /><br/>
                                <QueriesSection queries={nodeData.details.queries} /><br/>
                                <ResourcesSection resources={nodeData.details.resources} /><br/>
                                <OperationsSection operations={nodeData.details.operations} />
                            </Box>
                        </>}
                    />
                </Grid>
            </Grid>
        </div>
    );
}

function FaultyDataServiceDetailTable(props) {
    const nodeData = props.nodeData;
    return <Table>
        <TableBody>
            <TableRow>
                <TableCell>Data Service Name</TableCell>
                <TableCell>{nodeData.name}</TableCell>
            </TableRow>
            <TableRow>
                <TableCell>Status</TableCell>
                <TableCell>
                    <span style={{display:'flex', alignItems:'center', gap:4, color:'red'}}>
                        <DisabledIcon fontSize="small"/> FAULTY
                    </span>
                </TableCell>
            </TableRow>
            {nodeData.errorMessage && (
                <TableRow>
                    <TableCell>Error Message</TableCell>
                    <TableCell style={{color: 'red'}}>{nodeData.errorMessage}</TableCell>
                </TableRow>
            )}
        </TableBody>
    </Table>
}

function StackTraceSection({ groupId, nodeId, serviceName }) {
    const [expanded, setExpanded] = React.useState(false);
    const [stackTrace, setStackTrace] = React.useState(null);
    const [loading, setLoading] = React.useState(false);
    const [error, setError] = React.useState(null);
    const [hasFetched, setHasFetched] = React.useState(false);

    const handleChange = (event, isExpanded) => {
        if (isExpanded && !hasFetched && !loading) {
            setLoading(true);
            setError(null);
            HTTPClient.getDataServiceFaultDetails(groupId, nodeId, serviceName)
                .then(res => {
                    setStackTrace(res.data.faultStackTrace || null);
                    setHasFetched(true);
                    setLoading(false);
                })
                .catch(() => {
                    setError('Failed to load stack trace.');
                    setHasFetched(true);
                    setLoading(false);
                });
        }
        setExpanded(isExpanded);
    };

    return (
        <ExpansionPanel expanded={expanded} onChange={handleChange}>
            <ExpansionPanelSummary expandIcon={<ExpandMoreIcon />}>
                <Typography>Stack Trace</Typography>
            </ExpansionPanelSummary>
            <ExpansionPanelDetails>
                {loading && <CircularProgress size={20} />}
                {error && <Typography color="error">{error}</Typography>}
                {!loading && !error && stackTrace && (
                    <pre style={{ overflowX: 'auto', maxHeight: 300, fontSize: 12, width: '100%', whiteSpace: 'pre' }}>
                        {stackTrace}
                    </pre>
                )}
                {!loading && !error && !stackTrace && (
                    <Typography variant="body2" color="textSecondary">No stack trace available.</Typography>
                )}
            </ExpansionPanelDetails>
        </ExpansionPanel>
    );
}

function DataServicesDetailTable(props) {
    const nodeData = props.nodeData;
    const wsdl1_1 = nodeData.wsdl1_1;
    const swagger_url = nodeData.swagger_url;
    const classes = useStyles();
    return <Table>
        <TableBody>
            <TableRow>
                <TableCell>Data Service Name</TableCell>
                <TableCell>{nodeData.serviceName}</TableCell>
            </TableRow>
            <TableRow>
                <TableCell>Status</TableCell>
                <TableCell>
                    <span style={{display:'flex', alignItems:'center', gap:4, color:'green'}}>
                        <EnabledIcon fontSize="small"/> ACTIVE
                    </span>
                </TableCell>
            </TableRow>
            <TableRow>
                <TableCell>WSDL 1.1</TableCell>
                <TableCell><a className={classes.url} href={wsdl1_1}>{wsdl1_1}</a></TableCell>
            </TableRow>
            <TableRow>
                <TableCell>Swagger URL</TableCell>
                <TableCell><a className={classes.url} href={swagger_url}>{swagger_url}</a></TableCell>
            </TableRow>
        </TableBody>
    </Table>
}

function DataSourcesSection(props) {
    return <Box pl={2}>
                <Typography variant="h6" color="inherit" noWrap>
                    Data Sources
                </Typography>
                <Box pr={2}>
                    <DataSourcesDetailTable dataSources={props.dataSources} />
                </Box>
            </Box>
}

function DataSourcesDetailTable(props) {
    const dataSources = props.dataSources;
    return (dataSources.map(dataSource => (
        <ExpansionPanel>
            <ExpansionPanelSummary
                expandIcon={<ExpandMoreIcon />}
                aria-controls="panel1a-content"
                id="panel1a-header">
                {dataSource.dataSourceId}
            </ExpansionPanelSummary>
            <ExpansionPanelDetails>
                <Table size="small">
                    <TableBody>
                        <TableRow>
                            <TableCell>Data Source ID</TableCell>
                            <TableCell>{dataSource.dataSourceId}</TableCell>
                        </TableRow>
                        <TableRow>
                            <TableCell>Data Source Type</TableCell>
                            <TableCell>{dataSource.dataSourceType}</TableCell>
                        </TableRow>
                    </TableBody>
                </Table>
            </ExpansionPanelDetails>
        </ExpansionPanel>
    ))
    );
}

function QueriesSection(props) {
    return <Box pl={2}>
                <Typography variant="h6" color="inherit" noWrap>
                    Queries
                </Typography>
                <Box pr={2}>
                    <QueriesDetailTable queries={props.queries} />
                </Box>
            </Box>
}

function QueriesDetailTable(props) {
    const queries = props.queries;

    return (
        queries.map(query => (
            <ExpansionPanel>
                <ExpansionPanelSummary
                    expandIcon={<ExpandMoreIcon />}
                    aria-controls="panel1a-content"
                    id="panel1a-header">
                    {query.id}
                </ExpansionPanelSummary>
                <ExpansionPanelDetails>
                    <Table size="small">
                        <TableBody>
                            <TableRow>
                                <TableCell>Query ID</TableCell>
                                <TableCell>{query.id}</TableCell>
                            </TableRow>
                            <TableRow>
                                <TableCell>Data Source</TableCell>
                                <TableCell>{query.dataSourceId}</TableCell>
                            </TableRow>
                            <TableRow>
                                <TableCell>Namespace</TableCell>
                                <TableCell>{query.namespace}</TableCell>
                            </TableRow>
                        </TableBody>
                    </Table>
                </ExpansionPanelDetails>
            </ExpansionPanel>
        ))
    );
}

function ResourcesSection(props) {
    return <Box pl={2}>
                <Typography variant="h6" color="inherit" noWrap>
                    Resources
                </Typography>
                <Box pr={2}>
                    <ResourcesDetailTable resources={props.resources} />
                </Box>
            </Box>
}

function ResourcesDetailTable(props) {
    const resources = props.resources;

    return (
        resources.map(resource => (
            <ExpansionPanel>
                <ExpansionPanelSummary
                    expandIcon={<ExpandMoreIcon />}
                    aria-controls="panel1a-content"
                    id="panel1a-header">
                    {resource.resourcePath}
                </ExpansionPanelSummary>
                <ExpansionPanelDetails>
                    <Table size="small">
                        <TableBody>
                            <TableRow>
                                <TableCell>Resource Path</TableCell>
                                <TableCell>{resource.resourcePath}</TableCell>
                            </TableRow>
                            <TableRow>
                                <TableCell>Resource Method</TableCell>
                                <TableCell>{resource.resourceMethod}</TableCell>
                            </TableRow>
                            <TableRow>
                                <TableCell>Query ID</TableCell>
                                <TableCell>{resource.resourceQuery}</TableCell>
                            </TableRow>
                        </TableBody>
                    </Table>
                </ExpansionPanelDetails>
            </ExpansionPanel>
        ))
    );
}

function OperationsSection(props) {
    return <Box pl={2}>
                <Typography variant="h6" color="inherit" noWrap>
                    Operations
                </Typography>
                <Box pr={2}>
                    <OperationsDetailTable operations={props.operations} />
                </Box>
            </Box>
}

function OperationsDetailTable(props) {
    const operations = props.operations;

    return (
        operations.map(operation => (
            <ExpansionPanel>
                <ExpansionPanelSummary
                    expandIcon={<ExpandMoreIcon />}
                    aria-controls="panel1a-content"
                    id="panel1a-header">
                    {operation.operationName}
                </ExpansionPanelSummary>
                <ExpansionPanelDetails>
                    <Table size="small">
                        <TableBody>
                            <TableRow>
                                <TableCell>Operation Name</TableCell>
                                <TableCell>{operation.operationName}</TableCell>
                            </TableRow>
                            <TableRow>
                                <TableCell>Query ID</TableCell>
                                <TableCell>{operation.queryName}</TableCell>
                            </TableRow>
                        </TableBody>
                    </Table>
                </ExpansionPanelDetails>
            </ExpansionPanel>
        ))
    );
}

const useStyles = makeStyles((theme) => ({
    root: {
        flexGrow: 1,
        width: 700,
        overflowX: 'hidden',
    },
    paper: {
        padding: theme.spacing(2),
    },
    url: {
        color: '#3f51b5'
    }
}));
